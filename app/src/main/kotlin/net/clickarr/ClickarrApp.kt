package net.clickarr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import net.clickarr.feature.channels.ChannelEditorScreen
import net.clickarr.feature.player.PlayerScreen
import net.clickarr.feature.setup.SetupScreen
import net.clickarr.ui.design.ClickarrColors

/**
 * Start route: setup when no server is connected, otherwise the player (television starts playing).
 * The shell (Guide, Channels, Favorites, Settings) sits behind Back from the player.
 */
@Composable
fun ClickarrApp(onExit: () -> Unit, viewModel: AppViewModel = hiltViewModel()) {
    val ready by viewModel.ready.collectAsState()
    val hasServer by viewModel.hasServer.collectAsState()
    val hadChannels by viewModel.hadChannelsAtStart.collectAsState()
    val uiScale by viewModel.uiScale.collectAsState()
    if (!ready) {
        Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase))
        return
    }
    val nav = rememberNavController()
    // With a server but no channels, the Channels tab is the only useful place to land; the player would be black.
    val start = remember { if (!hasServer) Routes.SETUP else if (hadChannels) Routes.PLAYER else Routes.shell() }
    // Settings, Appearance, Size: the design is drawn for 1080p at xhdpi, which fills a TV edge to edge.
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * uiScale, base.fontScale)) {
        AppNav(nav, start, onExit)
    }
}

@Composable
private fun AppNav(nav: NavHostController, start: String, onExit: () -> Unit) {
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.SETUP) {
            SetupScreen(onDone = { nav.navigate(Routes.shell()) { popUpTo(Routes.SETUP) { inclusive = true } } })
        }
        composable(Routes.PLAYER) {
            PlayerScreen(
                onOpenGuide = { nav.navigate(Routes.shell("guide")) },
                onOpenShell = { nav.navigate(Routes.shell()) },
            )
        }
        composable(
            Routes.SHELL,
            arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "channels" }),
        ) { entry ->
            ShellScreen(
                initialTab = entry.arguments?.getString("tab") ?: "channels",
                onWatch = { nav.navigate(Routes.PLAYER) { popUpTo(Routes.PLAYER) { inclusive = true } } },
                onCreateChannel = { nav.navigate(Routes.editor()) },
                onEditChannel = { nav.navigate(Routes.editor(it.value)) },
                onDisconnected = { nav.navigate(Routes.SETUP) { popUpTo(0) { inclusive = true } } },
                onExit = onExit,
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("channel") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ChannelEditorScreen(channelId = entry.arguments?.getString("channel"), onDone = { nav.popBackStack() })
        }
    }
}
