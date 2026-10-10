package net.clickarr.household.coordinator

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import net.clickarr.core.common.ClickarrError
import net.clickarr.core.common.Outcome
import net.clickarr.core.model.DeviceId
import net.clickarr.household.protocol.Command
import net.clickarr.household.protocol.CommandResponse
import net.clickarr.household.protocol.ErrorResponse
import net.clickarr.household.protocol.Event
import net.clickarr.household.protocol.PROTOCOL_VERSION
import net.clickarr.household.protocol.PairCompleteRequest
import net.clickarr.household.protocol.PairStartRequest
import net.clickarr.household.protocol.ProtocolJson

/** HTTP surface from proposal 14.2. Install on any Ktor engine. */
fun Application.coordinatorRoutes(coordinator: Coordinator) {
    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets)

    routing {
        get("/v1/info") { call.respond(coordinator.info()) }

        post("/v1/pair/start") {
            val req = call.receive<PairStartRequest>()
            call.respondOutcome(coordinator.pairStart(req))
        }

        post("/v1/pair/complete") {
            val req = call.receive<PairCompleteRequest>()
            call.respondOutcome(coordinator.pairComplete(req))
        }

        get("/v1/state") {
            val device = call.authenticated(coordinator) ?: return@get
            coordinator.markSeen(device)
            val state = coordinator.tick()
            val etag = "\"${state.revision}\""
            if (call.request.header("If-None-Match") == etag) {
                call.respond(HttpStatusCode.NotModified)
            } else {
                call.response.headers.append("ETag", etag)
                call.respond(state)
            }
        }

        get("/v1/lineups/{id}") {
            call.authenticated(coordinator) ?: return@get
            val id = call.parameters["id"]
            val lineup = coordinator.state.value.lineups.firstOrNull { it.id.value == id }
            if (lineup == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorResponse.NOT_FOUND, "No lineup $id"))
            } else {
                call.respond(lineup)
            }
        }

        post("/v1/commands") {
            val device = call.authenticated(coordinator) ?: return@post
            val command = call.receive<Command>()
            when (val r = coordinator.apply(command, device)) {
                is Outcome.Success -> call.respond(CommandResponse(r.value.revision))
                is Outcome.Failure -> call.respondError(r.error)
            }
        }

        get("/v1/devices") {
            call.authenticated(coordinator) ?: return@get
            call.respond(coordinator.state.value.devices)
        }

        delete("/v1/devices/{id}") {
            val device = call.authenticated(coordinator) ?: return@delete
            val target = DeviceId(call.parameters["id"].orEmpty())
            when (val r = coordinator.apply(Command.RemoveDevice(target), device)) {
                is Outcome.Success -> call.respond(CommandResponse(r.value.revision))
                is Outcome.Failure -> call.respondError(r.error)
            }
        }

        webSocket("/v1/events") {
            val token = call.request.queryParameters["token"] ?: call.request.header("Authorization")?.removePrefix("Bearer ")
            if (coordinator.authenticate(token) == null) {
                close()
                return@webSocket
            }
            val pings = flow {
                while (true) {
                    delay(PING_INTERVAL_MS)
                    emit(Event.Ping(coordinator.info().now))
                }
            }
            merge(coordinator.events, pings)
                .onEach { send(Frame.Text(ProtocolJson.encodeToString(Event.serializer(), it))) }
                .collect()
        }
    }
}

private const val PING_INTERVAL_MS = 30_000L

private suspend fun RoutingCall.authenticated(coordinator: Coordinator): DeviceId? {
    val bearer = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    val device = coordinator.authenticate(bearer)
    if (device == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorResponse.UNAUTHORIZED, "This device is not paired with the household"))
    }
    return device
}

private suspend inline fun <reified T : Any> RoutingCall.respondOutcome(outcome: Outcome<T>) {
    when (outcome) {
        is Outcome.Success -> respond(outcome.value)
        is Outcome.Failure -> respondError(outcome.error)
    }
}

private suspend fun RoutingCall.respondError(error: ClickarrError) {
    val (status, code) = when (error) {
        is ClickarrError.Unauthorized -> HttpStatusCode.Unauthorized to ErrorResponse.UNAUTHORIZED
        is ClickarrError.NotFound -> HttpStatusCode.NotFound to ErrorResponse.NOT_FOUND
        is ClickarrError.Invalid -> HttpStatusCode.BadRequest to ErrorResponse.INVALID
        is ClickarrError.Unsupported -> HttpStatusCode.BadRequest to ErrorResponse.VERSION
        is ClickarrError.Unreachable, is ClickarrError.Unknown -> HttpStatusCode.InternalServerError to ErrorResponse.CONFLICT
    }
    respond(status, ErrorResponse(code, error.message))
}
