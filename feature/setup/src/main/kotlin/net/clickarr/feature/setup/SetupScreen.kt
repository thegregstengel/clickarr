package net.clickarr.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.clickarr.ui.design.R as DesignR
import net.clickarr.ui.design.ClickarrLogoHorizontal
import androidx.tv.material3.ListItem
import androidx.tv.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.clickarr.ui.design.ClickarrColors
import net.clickarr.ui.design.ClickarrDimens
import net.clickarr.ui.design.ClickarrTextStyles

/** First-run flow (design language section 5, "Setup"). Centered column, one card per step. */
@Composable
fun SetupScreen(onDone: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val step by viewModel.step.collectAsState()
    LaunchedEffect(step) { if (step is SetupViewModel.Step.Done) onDone() }

    // The mockup sits the card in a dim living room. Two soft glows on the brand dark stand in for the photo.
    Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase).background(roomGlow()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(760.dp).padding(horizontal = ClickarrDimens.SafeArea, vertical = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ClickarrLogoHorizontal(markSize = 56.dp)
            Text("Turn your media library into TV", style = ClickarrTextStyles.RowTitle, color = ClickarrColors.TextPrimary)
            Card {
                when (val s = step) {
                    SetupViewModel.Step.Welcome -> Welcome(viewModel)
                    is SetupViewModel.Step.Linking -> Linking(s, viewModel)
                    is SetupViewModel.Step.ChooseServer -> ChooseServer(s, viewModel)
                    is SetupViewModel.Step.Connecting -> Status("Connecting to ${s.name}…")
                    SetupViewModel.Step.Manual -> Manual(viewModel)
                    is SetupViewModel.Step.Done -> Status("Connected to ${s.serverName}")
                    is SetupViewModel.Step.Failed -> Failed(s, viewModel)
                }
            }
        }
    }
}

@Composable
private fun roomGlow(): Brush = Brush.radialGradient(
    colors = listOf(ClickarrColors.AccentPrimaryDeep.copy(alpha = 0.55f), Color.Transparent),
    center = Offset(0.25f * GLOW_SPAN, 0.2f * GLOW_SPAN),
    radius = GLOW_SPAN,
)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ClickarrColors.BgPanel.copy(alpha = 0.92f), RoundedCornerShape(ClickarrDimens.RadiusCard))
            .border(1.dp, ClickarrColors.BgCellBorder, RoundedCornerShape(ClickarrDimens.RadiusCard))
            .padding(ClickarrDimens.CardPadding + 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/** The mockup's "Connect to Your Media Server" card: a row per way in, each with a tile and a chevron. */
@Composable
private fun Welcome(vm: SetupViewModel) {
    Text("Connect to your media server", style = ClickarrTextStyles.ScreenTitle)
    Text("Choose your server and sign in to get started.", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
    ConnectRow("Plex", "Sign in with your Plex account", DesignR.drawable.ic_ui_server, ClickarrColors.StatusWarn, vm::startPlexLink)
    ConnectRow(
        "Enter address manually", "A server address and token", DesignR.drawable.ic_ui_settings, ClickarrColors.AccentPrimary, vm::startManual,
    )
}

@Composable
private fun ConnectRow(title: String, subtitle: String, icon: Int, tint: Color, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, style = ClickarrTextStyles.RowTitle) },
        supportingContent = { Text(subtitle, style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary) },
        leadingContent = {
            Box(
                Modifier.size(44.dp).background(tint.copy(alpha = 0.18f), RoundedCornerShape(ClickarrDimens.RadiusCell)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = tint)
            }
        },
        trailingContent = {
            Icon(painterResource(DesignR.drawable.ic_ui_chevron_right), contentDescription = null, Modifier.size(24.dp))
        },
    )
}

@Composable
private fun Linking(s: SetupViewModel.Step.Linking, vm: SetupViewModel) {
    Text("On a phone or computer, go to", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
    Text("plex.tv/link", style = ClickarrTextStyles.ScreenTitle)
    Text("and enter this code", style = ClickarrTextStyles.Secondary, color = ClickarrColors.TextSecondary)
    Text(
        s.code.chunked(1).joinToString("  "),
        style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 72.sp, letterSpacing = 4.sp),
        color = ClickarrColors.AccentGlow,
    )
    val min = s.secondsLeft / 60
    val sec = s.secondsLeft % 60
    Text("Code expires in %d:%02d".format(min, sec), style = ClickarrTextStyles.Caption, color = ClickarrColors.TextMuted)
    Button(onClick = vm::reset) { Text("Cancel") }
}

@Composable
private fun ChooseServer(s: SetupViewModel.Step.ChooseServer, vm: SetupViewModel) {
    Text("Choose a server", style = ClickarrTextStyles.ScreenTitle)
    s.servers.forEach { candidate ->
        Button(onClick = { vm.choose(candidate) }) {
            Column {
                Text(candidate.server.name, style = ClickarrTextStyles.RowTitle)
                Text(candidate.server.urls.firstOrNull() ?: "", style = ClickarrTextStyles.Caption, color = ClickarrColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Manual(vm: SetupViewModel) {
    var url by remember { mutableStateOf("http://") }
    var token by remember { mutableStateOf("") }
    Text("Server address and token", style = ClickarrTextStyles.ScreenTitle)
    TvField("Address, e.g. http://192.168.1.20:32400", url, "setup.url") { url = it }
    TvField("X-Plex-Token", token, "setup.token") { token = it }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.connectManual(url, token) }) { Text("Connect") }
        Button(onClick = vm::reset) { Text("Back") }
    }
}

@Composable
private fun TvField(placeholder: String, value: String, tag: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .background(ClickarrColors.BgCell, RoundedCornerShape(ClickarrDimens.RadiusCell))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = ClickarrTextStyles.RowTitle, color = ClickarrColors.TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = ClickarrTextStyles.RowTitle.copy(color = ClickarrColors.TextPrimary),
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun Status(text: String) {
    Text(text, style = ClickarrTextStyles.ScreenTitle)
}

@Composable
private fun Failed(s: SetupViewModel.Step.Failed, vm: SetupViewModel) {
    Text("Something went wrong", style = ClickarrTextStyles.ScreenTitle)
    Text(s.message, style = ClickarrTextStyles.Secondary, color = ClickarrColors.StatusError)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.retryable) Button(onClick = vm::startPlexLink) { Text("Try again") }
        Button(onClick = vm::reset) { Text("Start over") }
    }
    Text("", style = MaterialTheme.typography.labelSmall)
}

private const val GLOW_SPAN = 1400f
