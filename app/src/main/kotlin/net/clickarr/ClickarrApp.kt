package net.clickarr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    if (!ready) {
        Box(Modifier.fillMaxSize().background(ClickarrColors.BgBase))
        return
    }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = if (hasServer) Routes.PLAYER else Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(onDone = { nav.navigate(Routes.SHELL) { popUpTo(Routes.SETUP) { inclusive = true } } })
        }
        composable(Routes.PLAYER) {
            PlayerScreen(
                onOpenGuide = { nav.navigate(Routes.SHELL) },
                onOpenShell = { nav.navigate(Routes.SHELL) },
            )
        }
        composable(Routes.SHELL) {
            ShellScreen(
                onWatch = { nav.navigate(Routes.PLAYER) { popUpTo(Routes.PLAYER) { inclusive = true } } },
                onCreateChannel = { nav.navigate(Routes.EDITOR) },
                onExit = onExit,
            )
        }
        composable(Routes.EDITOR) {
            ChannelEditorScreen(onDone = { nav.popBackStack() })
        }
    }
}
