package net.clickarr.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

    Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(880.dp).padding(ClickarrDimens.SafeArea),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Clickarr", style = ClickarrTextStyles.ProgramTitle)
            Text("Turn your media library into TV", style = ClickarrTextStyles.ScreenTitle, color = ClickarrColors.TextSecondary)
            Spacer(Modifier.height(8.dp))
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
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .background(ClickarrColors.BgElevated, RoundedCornerShape(ClickarrDimens.RadiusCard))
            .padding(ClickarrDimens.CardPadding + 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Welcome(vm: SetupViewModel) {
    Text("Connect to your Plex server", style = ClickarrTextStyles.ScreenTitle)
    Text(
        "Sign in with your Plex account to find your server, or enter an address and token.",
        style = ClickarrTextStyles.Secondary,
        color = ClickarrColors.TextSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::startPlexLink) { Text("Sign in with Plex") }
        Button(onClick = vm::startManual) { Text("Enter address manually") }
    }
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
