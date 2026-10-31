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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
    val sleepBlocking = sleepState != SleepGuard.State.Awake
    BackHandler(enabled = !sleepBlocking) {
        if (!viewModel.handleBack()) onRootBack(onOpenShell)
    }
    val focus = remember { FocusRequester() }
    val gearFocus = remember { FocusRequester() }
    var gearFocused by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusHost = when {
        streamPanel -> PlayerFocusHost.StreamPanel
        miniGuide != null -> PlayerFocusHost.MiniGuide
        gearFocused && overlayVisible -> PlayerFocusHost.Gear
        else -> PlayerFocusHost.Park
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(overlayVisible, streamPanel, miniGuide) {
        when {
            streamPanel -> Unit
            miniGuide != null -> runCatching { focus.requestFocus() }
            overlayVisible -> runCatching { gearFocus.requestFocus() }
            else -> {
                gearFocused = false
                runCatching { focus.requestFocus() }
            }
        }
    }
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
                playerKey(
                    event,
                    viewModel,
                    focusHost,
                    onOpenGuide = onOpenGuide,
                    onFocusGear = {
                        viewModel.closeMiniGuide()
                        viewModel.showOverlay()
                        runCatching { gearFocus.requestFocus() }
                    },
                )
            },
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

        GapLayer(tune, promoState, viewModel.now(), overlayVisible)

        AnimatedVisibility(
            visible = overlayVisible && tune.channel != null,
            enter = fadeIn(tween(ClickarrMotion.ENTER_MS)) + slideInVertically(tween(ClickarrMotion.ENTER_MS)) { it / 6 },
            exit = fadeOut(tween(ClickarrMotion.EXIT_MS)) + slideOutVertically(tween(ClickarrMotion.EXIT_MS)) { it / 6 },
        ) {
            ChannelOverlay(
                tune,
                now = viewModel.now(),
                digits = digits,
                captionsOn = captionsOn,
                sleepInMinutes = viewModel.sleepInMinutes(),
                gearFocus = gearFocus,
                onGearFocused = { gearFocused = it },
                onOpenStreams = viewModel.streams::open,
            )
        }
        if (streamPanel) {
            StreamOptionsPanel(viewModel.streams, onDismiss = viewModel.streams::close)
        }
        TopLayers(notice, sleepState)
        if (digits.isNotEmpty() && !overlayVisible) DigitBadge(digits)
        miniGuide?.let { MiniGuide(it, now = viewModel.now(), modifier = Modifier.align(Alignment.BottomCenter)) }
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

/**
 * Key-down events, except OK, which is a short press (the overlay) or a hold (captions) and so is decided on
 * key-up. The remote's own captions key, where there is one, switches captions too. The stream panel and
 * mini-guide take the keys they own while they are up.
 */
private fun playerKey(
    event: KeyEvent,
    vm: PlayerViewModel,
    host: PlayerFocusHost,
    onOpenGuide: () -> Unit,
    onFocusGear: () -> Unit,
): Boolean {
    val native = event.nativeKeyEvent
    val down = event.type == KeyEventType.KeyDown
    val ok = event.key == Key.DirectionCenter || event.key == Key.Enter
    return when {
        // Any button answers "Are you still watching?" or wakes the player, and goes no further.
        down && native.repeatCount == 0 && vm.sleep.onInput() -> true
        // Audio & video list owns OK and D-pad via Compose focus; do not steal them for channel change.
        passesToFocusedChild(host, event.key) -> false
        host != PlayerFocusHost.StreamPanel && ok -> okKey(event, vm, onGear = host == PlayerFocusHost.Gear)
        !down -> false
        native.keyCode == android.view.KeyEvent.KEYCODE_CAPTIONS -> true.also { vm.toggleCaptions() }
        host == PlayerFocusHost.MiniGuide -> handleMiniGuideKey(event.key, vm, onFocusGear)
        else -> handleKey(event.key, vm, onOpenGuide, onFocusGear)
    }
}

/** OK is a tap (overlay or gear) or a hold (captions), so both its down and up events go to the gesture. */
private fun okKey(event: KeyEvent, vm: PlayerViewModel, onGear: Boolean): Boolean {
    val native = event.nativeKeyEvent
    val gesture = if (onGear) vm.gearOk else vm.ok
    when (event.type) {
        KeyEventType.KeyDown -> gesture.down(native.eventTime, repeat = native.repeatCount > 0)
        KeyEventType.KeyUp -> gesture.up()
        else -> Unit
    }
    return true
}

/** Remote mapping from design language 2.8, with Left toward the stream gear. Returns true when consumed. */
private fun handleKey(key: Key, vm: PlayerViewModel, onOpenGuide: () -> Unit, onFocusGear: () -> Unit): Boolean {
    val action: (() -> Unit)? = when (key) {
        Key.DirectionUp, Key.ChannelUp -> vm::channelUp
        Key.DirectionDown, Key.ChannelDown -> vm::channelDown
        Key.Menu -> onOpenGuide
        Key.DirectionLeft -> onFocusGear
        Key.DirectionRight -> vm::openMiniGuide
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> vm::playPause
        in DIGIT_KEYS -> ({ vm.enterDigit(DIGIT_KEYS.indexOf(key)) })
        else -> null
    }
    action?.invoke()
    return action != null
}

/**
 * Inside the mini-guide: Right moves ahead; Left moves back or, on the first card, returns to the gear.
 * OK closes (Back closes through the BackHandler).
 */
private fun handleMiniGuideKey(key: Key, vm: PlayerViewModel, onFocusGear: () -> Unit): Boolean {
    when (key) {
        Key.DirectionLeft -> {
            val guide = vm.miniGuide.value
            if (guide != null && guide.index > 0) vm.moveMiniGuide(-1) else onFocusGear()
        }
        Key.DirectionRight -> vm.moveMiniGuide(+1)
        Key.DirectionCenter, Key.Enter -> vm.closeMiniGuide()
        else -> return false
    }
    return true
}

private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private fun <T> tween(ms: Int) = androidx.compose.animation.core.tween<T>(durationMillis = ms)
