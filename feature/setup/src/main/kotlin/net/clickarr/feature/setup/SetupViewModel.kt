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
import net.clickarr.provider.plex.PlexAuth
import net.clickarr.provider.plex.PlexServerCandidate

/**
 * Plex sign-in for a television (proposal 6.2): show a code, poll plex.tv until the user claims it,
 * then pick a server. Manual URL + token is the fallback for people who avoid plex.tv login.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val auth: PlexAuth,
    private val connector: ServerConnector,
) : ViewModel() {
    sealed interface Step {
        data object Welcome : Step
        data class Linking(val code: String, val secondsLeft: Int) : Step
        data class ChooseServer(val servers: List<PlexServerCandidate>) : Step
        data class Connecting(val name: String) : Step
        data object Manual : Step
        data class Done(val serverName: String) : Step
        data class Failed(val message: String, val retryable: Boolean = true) : Step
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
        _step.value = when (val r = connector.connectPlex(candidate)) {
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
            _step.value = when (val r = connector.connectPlexManual(baseUrl.trim(), token.trim())) {
                is Outcome.Success -> Step.Done(r.value.server.name)
                is Outcome.Failure -> Step.Failed(r.error.message)
            }
        }
    }

    fun reset() {
        polling?.cancel()
        _step.value = Step.Welcome
    }

    companion object {
        private const val TAG = "Setup"
        private const val PIN_LIFETIME_SECONDS = 15 * 60
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
