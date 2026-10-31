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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import net.clickarr.playback.core.TuneState
import net.clickarr.playback.core.TuneStatus
import net.clickarr.ui.design.ClickarrMotion

/**
 * Full-screen video with the overlay laid on top (design language 4, "Playback overlay").
 * The remote is handled here; the view model owns the player.
 *
 * While a channel plays: Up/Down change channel; Left moves focus toward the Audio & video gear;
 * Right opens the mini-guide (upcoming). From the mini-guide, Left returns toward the gear.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onOpenGuide: () -> Unit,
    onOpenShell: () -> Unit,
    /** Root Back after mini-guide and overlay are closed: arm double-Back or open the shell. */
    onRootBack: (navigate: () -> Unit) -> Boolean = { navigate -> navigate(); true },
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val tune by viewModel.tune.collectAsState()
    val overlayVisible by viewModel.overlayVisible.collectAsState()
    val digits by viewModel.digits.collectAsState()
    val captionsOn by viewModel.captions.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val promoState by viewModel.promo.collectAsState()
    val sleepState by viewModel.sleep.state.collectAsState()
    val miniGuide by viewModel.miniGuide.collectAsState()
    val streamPanel by viewModel.streams.open.collectAsState()
    // Back arrives as a key on Fire OS but through the back dispatcher on newer Android (predictive back),
    // so it is handled here rather than in the key map: close the stream panel, mini-guide, then overlay, then leave.
    // Sleep Ask/Asleep consumes keys in playerKey; do not show exit confirm over them.
    BackHandler(enabled = sleepState == SleepGuard.State.Awake) {
        if (!viewModel.handleBack()) onRootBack(onOpenShell)
    }
    val parkFocus = remember { FocusRequester() }
    val gearFocus = remember { FocusRequester() }
    var gearFocused by remember { mutableStateOf(false) }
    val focusHost = resolvePlayerFocusHost(streamPanel, miniGuide != null, gearFocused, overlayVisible)
    PlayerLifecycle(viewModel)
    SyncPlayerFocus(overlayVisible, streamPanel, miniGuide != null, parkFocus, gearFocus) { gearFocused = false }

    PlayerSurface(
        viewModel = viewModel,
        focusHost = focusHost,
        parkFocus = parkFocus,
        onOpenGuide = onOpenGuide,
        onFocusGear = {
            viewModel.closeMiniGuide()
            viewModel.showOverlay()
            runCatching { gearFocus.requestFocus() }
        },
    ) {
        GapLayer(tune, promoState, viewModel.now(), overlayVisible)
        PlayerOverlayLayer(
            visible = overlayVisible && tune.channel != null,
            tune = tune,
            now = viewModel.now(),
            digits = digits,
            captionsOn = captionsOn,
            sleepInMinutes = viewModel.sleepInMinutes(),
            streamGear = StreamGearBinding(gearFocus) { gearFocused = it },
        )
        if (streamPanel) StreamOptionsPanel(viewModel.streams, onDismiss = viewModel.streams::close)
        TopLayers(notice, sleepState)
        if (digits.isNotEmpty() && !overlayVisible) DigitBadge(digits)
        miniGuide?.let { MiniGuide(it, now = viewModel.now(), modifier = Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun PlayerLifecycle(viewModel: PlayerViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Keep Compose focus on the park, gear, or stream panel as those surfaces open and close. */
@Composable
private fun SyncPlayerFocus(
    overlayVisible: Boolean,
    streamPanel: Boolean,
    miniGuideOpen: Boolean,
    parkFocus: FocusRequester,
    gearFocus: FocusRequester,
    onPark: () -> Unit,
) {
    LaunchedEffect(Unit) { parkFocus.requestFocus() }
    LaunchedEffect(overlayVisible, streamPanel, miniGuideOpen) {
        when {
            streamPanel -> Unit
            miniGuideOpen -> runCatching { parkFocus.requestFocus() }
            overlayVisible -> runCatching { gearFocus.requestFocus() }
            else -> {
                onPark()
                runCatching { parkFocus.requestFocus() }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    viewModel: PlayerViewModel,
    focusHost: PlayerFocusHost,
    parkFocus: FocusRequester,
    onOpenGuide: () -> Unit,
    onFocusGear: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(parkFocus)
            .focusable()
            .onPreviewKeyEvent { event -> playerKey(event, viewModel, focusHost, onOpenGuide, onFocusGear) },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.engine.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    subtitleView?.applyClickarrStyle()
                }
            },
            // Bounce screensaver needs the panel awake; the TV may still blank under its own display-sleep settings.
            update = { view -> view.keepScreenOn = true },
            modifier = Modifier.fillMaxSize(),
        )
        content()
    }
}

@Composable
private fun PlayerOverlayLayer(
    visible: Boolean,
    tune: TuneState,
    now: kotlinx.datetime.Instant,
    digits: String,
    captionsOn: Boolean,
    sleepInMinutes: Long?,
    streamGear: StreamGearBinding,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ClickarrMotion.ENTER_MS)) + slideInVertically(tween(ClickarrMotion.ENTER_MS)) { it / 6 },
        exit = fadeOut(tween(ClickarrMotion.EXIT_MS)) + slideOutVertically(tween(ClickarrMotion.EXIT_MS)) { it / 6 },
    ) {
        ChannelOverlay(
            tune,
            now = now,
            digits = digits,
            captionsOn = captionsOn,
            sleepInMinutes = sleepInMinutes,
            streamGear = streamGear,
        )
    }
}

/** Between programs and when nothing can play: the "Coming up next" promo, or the countdown and reason card. */
@Composable
private fun GapLayer(tune: TuneState, promo: PromoDirector.Promo?, now: kotlinx.datetime.Instant, overlayVisible: Boolean) {
    val status = tune.status
    when {
        status is TuneStatus.Filler && promo != null ->
            PromoCard(promo, tune.channel.takeIf { !overlayVisible }, now, showText = !overlayVisible)
        status is TuneStatus.Filler || status is TuneStatus.Unavailable || tune.channel == null ->
            FillerCard(
                tune,
                now,
                streamFailed = status is TuneStatus.Unavailable,
                reason = (status as? TuneStatus.Unavailable)?.reason,
                showHeader = !overlayVisible,
            )
    }
}

/** The short captions notice, and the still-watching question or the asleep screen over everything. */
@Composable
private fun TopLayers(notice: String?, sleepState: SleepGuard.State) {
    notice?.let { CaptionNotice(it) }
    when (sleepState) {
        is SleepGuard.State.Asking -> StillWatchingPrompt(sleepState.deadlineMs)
        SleepGuard.State.Asleep -> AsleepScreen()
        SleepGuard.State.Awake -> Unit
    }
}

private fun <T> tween(ms: Int) = androidx.compose.animation.core.tween<T>(durationMillis = ms)
