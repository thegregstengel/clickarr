package net.clickarr.feature.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import net.clickarr.playback.core.TuneStatus
import net.clickarr.ui.design.ClickarrMotion

/**
 * Full-screen video with the overlay laid on top (design language 4, "Playback overlay").
 * The remote is handled here; the view model owns the player.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onOpenGuide: () -> Unit,
    onOpenShell: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val tune by viewModel.tune.collectAsState()
    val overlayVisible by viewModel.overlayVisible.collectAsState()
    val digits by viewModel.digits.collectAsState()
    val miniGuide by viewModel.miniGuide.collectAsState()
    // Back arrives as a key on Fire OS but through the back dispatcher on newer Android (predictive back),
    // so it is handled here rather than in the key map: close the mini-guide, then the overlay, then leave.
    BackHandler {
        when {
            miniGuide != null -> viewModel.closeMiniGuide()
            overlayVisible -> viewModel.hideOverlay()
            else -> onOpenShell()
        }
    }
    val focus = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && if (miniGuide != null) {
                    handleMiniGuideKey(event.key, viewModel)
                } else {
                    handleKey(event.key, viewModel, onOpenGuide)
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.engine.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val status = tune.status
        if (status is TuneStatus.Filler || status is TuneStatus.Unavailable || tune.channel == null) {
            FillerCard(tune, viewModel.now(), reason = (status as? TuneStatus.Unavailable)?.reason, showHeader = !overlayVisible)
        }

        AnimatedVisibility(
            visible = overlayVisible && tune.channel != null,
            enter = fadeIn(tween(ClickarrMotion.ENTER_MS)) + slideInVertically(tween(ClickarrMotion.ENTER_MS)) { it / 6 },
            exit = fadeOut(tween(ClickarrMotion.EXIT_MS)) + slideOutVertically(tween(ClickarrMotion.EXIT_MS)) { it / 6 },
        ) {
            ChannelOverlay(tune, now = viewModel.now(), digits = digits)
        }
        if (digits.isNotEmpty() && !overlayVisible) DigitBadge(digits)
        miniGuide?.let { MiniGuide(it, now = viewModel.now(), modifier = Modifier.align(Alignment.BottomCenter)) }
    }
}

/** Remote mapping from design language 2.8. Returns true when the key was consumed. */
private fun handleKey(key: Key, vm: PlayerViewModel, onOpenGuide: () -> Unit): Boolean {
    val action: (() -> Unit)? = when (key) {
        Key.DirectionUp, Key.ChannelUp -> vm::channelUp
        Key.DirectionDown, Key.ChannelDown -> vm::channelDown
        Key.DirectionCenter, Key.Enter -> vm::toggleOverlay
        Key.Menu -> onOpenGuide
        Key.DirectionLeft, Key.DirectionRight -> vm::openMiniGuide
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> vm::playPause
        in DIGIT_KEYS -> ({ vm.enterDigit(DIGIT_KEYS.indexOf(key)) })
        else -> null
    }
    action?.invoke()
    return action != null
}

/** Inside the mini-guide: Left and Right move, OK closes (Back closes through the BackHandler). */
private fun handleMiniGuideKey(key: Key, vm: PlayerViewModel): Boolean {
    when (key) {
        Key.DirectionLeft -> vm.moveMiniGuide(-1)
        Key.DirectionRight -> vm.moveMiniGuide(+1)
        Key.DirectionCenter, Key.Enter -> vm.closeMiniGuide()
        else -> return false
    }
    return true
}

private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private fun <T> tween(ms: Int) = androidx.compose.animation.core.tween<T>(durationMillis = ms)
