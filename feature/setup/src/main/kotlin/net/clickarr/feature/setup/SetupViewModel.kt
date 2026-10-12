package net.clickarr.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.provider.api.ProviderCredentials
import net.clickarr.provider.jellyfin.JellyfinAuth
import net.clickarr.provider.plex.PlexAuth
import net.clickarr.provider.plex.PlexProviderFactory
import net.clickarr.provider.plex.PlexServerCandidate

/**
 * Sign-in for a television. Plex (proposal 6.2): show a code, poll plex.tv until the user claims it, then pick a
 * server; manual URL + token is the fallback for people who avoid plex.tv login. Jellyfin (ADR 0022): type the
 * server's address, then approve a Quick Connect code in a Jellyfin app or sign in with username and password.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class SetupViewModel @Inject constructor(
    private val auth: PlexAuth,
    private val jellyfin: JellyfinAuth,
    private val connector: ServerConnector,
) : ViewModel() {
    sealed interface Step {
        data object Welcome : Step
        data class Linking(val code: String, val secondsLeft: Int) : Step
        data class ChooseServer(val servers: List<PlexServerCandidate>) : Step
        data class Connecting(val name: String) : Step
        data object Manual : Step
        data class JellyfinAddress(val typed: String = "") : Step
        data class JellyfinSignIn(val baseUrl: String, val quickConnect: Boolean) : Step
        data class JellyfinQuickConnect(val baseUrl: String, val code: String) : Step
        data class JellyfinPassword(val baseUrl: String) : Step
        data class Done(val serverName: String) : Step

        /** [jellyfinUrl] set means "Try again" goes back to the Jellyfin address rather than plex.tv. */
        data class Failed(val message: String, val retryable: Boolean = true, val jellyfinUrl: String? = null) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Welcome)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var polling: Job? = null

    fun startPlexLink() {
        polling?.cancel()
        polling = viewModelScope.launch {
            val pin = when (val r = auth.createPin()) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    _step.value = Step.Failed("Could not reach plex.tv: ${r.error.message}")
                    return@launch
                }
            }
            var secondsLeft = PIN_LIFETIME_SECONDS
            _step.value = Step.Linking(pin.code, secondsLeft)
            while (secondsLeft > 0) {
                delay(POLL_INTERVAL_MS)
                secondsLeft -= (POLL_INTERVAL_MS / 1000).toInt()
                when (val r = auth.checkPin(pin)) {
                    is Outcome.Success -> {
                        val token = r.value
                        if (token != null) {
                            listServers(token)
                            return@launch
                        }
                        _step.value = Step.Linking(pin.code, secondsLeft)
                    }
                    is Outcome.Failure -> Log.w(TAG) { "pin poll failed: ${r.error.message}" }
                }
            }
            _step.value = Step.Failed("The code expired before it was entered.")
        }
    }

    private suspend fun listServers(accountToken: String) {
        when (val r = auth.servers(accountToken)) {
            is Outcome.Success -> {
                val servers = r.value
                when {
                    servers.isEmpty() -> _step.value = Step.Failed("This Plex account has no servers.", retryable = false)
                    servers.size == 1 -> connect(servers.single())
                    else -> _step.value = Step.ChooseServer(servers)
                }
            }
            is Outcome.Failure -> _step.value = Step.Failed("Signed in, but could not list servers: ${r.error.message}")
        }
    }

    fun choose(candidate: PlexServerCandidate) {
        viewModelScope.launch { connect(candidate) }
    }

    private suspend fun connect(candidate: PlexServerCandidate) {
        _step.value = Step.Connecting(candidate.server.name)
        val credentials = PlexProviderFactory.credentials(candidate.token)
        _step.value = when (val r = connector.connect(candidate.server.name, candidate.server.urls, credentials)) {
            is Outcome.Success -> Step.Done(r.value.server.name)
            is Outcome.Failure -> Step.Failed(r.error.message)
        }
    }

    fun startManual() {
        polling?.cancel()
        _step.value = Step.Manual
    }

    fun connectManual(baseUrl: String, token: String) {
        viewModelScope.launch {
            _step.value = Step.Connecting(baseUrl)
            val url = baseUrl.trim()
            _step.value = when (val r = connector.connect(url, listOf(url), PlexProviderFactory.credentials(token.trim()))) {
                is Outcome.Success -> Step.Done(r.value.server.name)
                is Outcome.Failure -> Step.Failed(r.error.message)
            }
        }
    }

    fun reset() {
        polling?.cancel()
        _step.value = Step.Welcome
    }

    /** "Try again" on the failure card: back to wherever the failed attempt started. */
    fun retry() {
        val failed = _step.value as? Step.Failed
        if (failed?.jellyfinUrl != null) startJellyfin(failed.jellyfinUrl) else startPlexLink()
    }

    fun startJellyfin(typed: String = "") {
        polling?.cancel()
        _step.value = Step.JellyfinAddress(typed)
    }

    /** Check the typed address answers as Jellyfin, then offer the ways to sign in it allows. */
    fun jellyfinAddress(typed: String) {
        val base = JellyfinAuth.normalize(typed)
        if (base == null) {
            _step.value = Step.Failed("That does not look like a server address.", jellyfinUrl = typed)
            return
        }
        viewModelScope.launch {
            _step.value = Step.Connecting(base)
            _step.value = when (val r = jellyfin.quickConnectEnabled(base)) {
                is Outcome.Success -> Step.JellyfinSignIn(base, quickConnect = r.value)
                is Outcome.Failure -> Step.Failed("No Jellyfin server answered at $base. ${r.error.message}", jellyfinUrl = typed)
            }
        }
    }

    fun jellyfinQuickConnect(base: String) {
        polling?.cancel()
        polling = viewModelScope.launch {
            val request = when (val r = jellyfin.startQuickConnect(base)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    _step.value = Step.Failed("Quick Connect did not start: ${r.error.message}", jellyfinUrl = base)
                    return@launch
                }
            }
            _step.value = Step.JellyfinQuickConnect(base, request.code)
            repeat(QUICK_CONNECT_POLLS) {
                delay(POLL_INTERVAL_MS)
                val approved = jellyfin.approved(base, request)
                if (approved is Outcome.Success && approved.value) {
                    when (val signIn = jellyfin.finishQuickConnect(base, request)) {
                        is Outcome.Success -> connectJellyfin(base, signIn.value.credentials)
                        is Outcome.Failure -> _step.value = Step.Failed(JellyfinAuth.explain(signIn.error), jellyfinUrl = base)
                    }
                    return@launch
                }
            }
            _step.value = Step.Failed("The code expired before it was approved.", jellyfinUrl = base)
        }
    }

    fun jellyfinPassword(base: String) {
        polling?.cancel()
        _step.value = Step.JellyfinPassword(base)
    }

    fun jellyfinSignIn(base: String, user: String, password: String) {
        viewModelScope.launch {
            _step.value = Step.Connecting(base)
            when (val r = jellyfin.password(base, user.trim(), password)) {
                is Outcome.Success -> connectJellyfin(base, r.value.credentials)
                is Outcome.Failure -> _step.value = Step.Failed(JellyfinAuth.explain(r.error), jellyfinUrl = base)
            }
        }
    }

    private suspend fun connectJellyfin(base: String, credentials: ProviderCredentials) {
        _step.value = Step.Connecting(base)
        _step.value = when (val r = connector.connect(base, listOf(base), credentials)) {
            is Outcome.Success -> Step.Done(r.value.server.name)
            is Outcome.Failure -> Step.Failed(r.error.message, jellyfinUrl = base)
        }
    }

    companion object {
        private const val TAG = "Setup"
        private const val PIN_LIFETIME_SECONDS = 15 * 60
        private const val POLL_INTERVAL_MS = 3_000L

        /** Jellyfin forgets an unapproved Quick Connect request after a few minutes; ten is generous. */
        private const val QUICK_CONNECT_POLLS = 200
    }
}
