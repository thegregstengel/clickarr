package net.clickarr.household.client

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Log
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.HouseholdState
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.CommandResponse
import net.clickarr.household.protocol.ErrorResponse
import net.clickarr.household.protocol.Event
import net.clickarr.household.protocol.InfoResponse
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairCompleteResponse
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.PairStartResponse
import net.clickarr.household.protocol.ProtocolJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A member's view of one coordinator (proposal 14.3). The OkHttpClient is supplied by the app so the
 * transport trust policy (pinned certificate or plain LAN HTTP) lives in one place.
 */
class HouseholdClient(
    private val http: OkHttpClient,
    val baseUrl: String,
    private val token: () -> String?,
) {
    sealed interface StateFetch {
        data object NotModified : StateFetch
        data class Fresh(val state: HouseholdState) : StateFetch
    }

    suspend fun info(): Outcome<InfoResponse> = get("/v1/info", InfoResponse.serializer())

    suspend fun pairStart(req: PairStartRequest): Outcome<PairStartResponse> =
        post("/v1/pair/start", req, PairStartRequest.serializer(), PairStartResponse.serializer())

    suspend fun pairComplete(req: PairCompleteRequest): Outcome<PairCompleteResponse> =
        post("/v1/pair/complete", req, PairCompleteRequest.serializer(), PairCompleteResponse.serializer())

    /** Full snapshot unless the coordinator's revision still equals [knownRevision]. */
    suspend fun state(knownRevision: Long?): Outcome<StateFetch> = withContext(Dispatchers.IO) {
        val request = request("/v1/state").get().apply {
            if (knownRevision != null) header("If-None-Match", "\"$knownRevision\"")
        }.build()
        execute(request) { response ->
            if (response.code == 304) {
                Outcome.Success(StateFetch.NotModified)
            } else {
                decode(response, HouseholdState.serializer()).map { StateFetch.Fresh(it) }
            }
        }
    }

    suspend fun send(command: Command): Outcome<Long> =
        post("/v1/commands", command, Command.serializer(), CommandResponse.serializer()).map { it.revision }

    /** Live revision notifications. Completes when the socket closes; callers reconnect with backoff. */
    fun events(): Flow<Event> = callbackFlow {
        val url = baseUrl.replaceFirst("http", "ws") + "/v1/events"
        val request = Request.Builder().url(url).apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
        val socket: WebSocket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { ProtocolJson.decodeFromString(Event.serializer(), text) }
                        .onSuccess { trySend(it) }
                        .onFailure { Log.w(TAG, it) { "bad event frame" } }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }
            },
        )
        awaitClose { socket.close(NORMAL_CLOSE, null) }
    }

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>): Outcome<T> = withContext(Dispatchers.IO) {
        execute(request(path).get().build()) { decode(it, strategy) }
    }

    private suspend fun <B, T> post(
        path: String,
        body: B,
        bodyStrategy: SerializationStrategy<B>,
        strategy: DeserializationStrategy<T>,
    ): Outcome<T> = withContext(Dispatchers.IO) {
        val json = ProtocolJson.encodeToString(bodyStrategy, body)
        execute(request(path).post(json.toRequestBody(JSON)).build()) { decode(it, strategy) }
    }

    private fun request(path: String): Request.Builder =
        Request.Builder().url(baseUrl.trimEnd('/') + path).apply { token()?.let { header("Authorization", "Bearer $it") } }

    private inline fun <T> execute(request: Request, handle: (Response) -> Outcome<T>): Outcome<T> = try {
        http.newCall(request).execute().use(handle)
    } catch (e: IOException) {
        Outcome.Failure(ClickarrError.Unreachable("Coordinator unreachable at $baseUrl", e))
    }

    private fun <T> decode(response: Response, strategy: DeserializationStrategy<T>): Outcome<T> {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) return Outcome.Failure(errorOf(response.code, text))
        return try {
            Outcome.Success(ProtocolJson.decodeFromString(strategy, text))
        } catch (e: Exception) {
            Outcome.Failure(ClickarrError.Unknown("Unexpected reply from coordinator", e))
        }
    }

    private fun errorOf(code: Int, text: String): ClickarrError {
        val parsed = runCatching { ProtocolJson.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull()
        val message = parsed?.message ?: "Coordinator returned HTTP $code"
        return when (code) {
            401, 403 -> ClickarrError.Unauthorized(message)
            404 -> ClickarrError.NotFound(message)
            400 -> ClickarrError.Invalid(message)
            else -> ClickarrError.Unknown(message)
        }
    }

    companion object {
        private const val TAG = "HouseholdClient"
        private const val NORMAL_CLOSE = 1000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
