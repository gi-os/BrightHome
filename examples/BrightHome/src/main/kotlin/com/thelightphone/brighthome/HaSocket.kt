package com.thelightphone.brighthome

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Everything the socket can tell the repository. */
sealed interface HaEvent {
    data object Connected : HaEvent
    data class StateChanged(val state: HaState) : HaEvent
    data class Areas(val areas: List<HaArea>, val entityArea: Map<String, String>) : HaEvent

    /** The registry needs an admin token; a non-admin one authenticates but cannot list areas. */
    data object AreasUnavailable : HaEvent
    data class AuthFailed(val message: String) : HaEvent
    data class Closed(val reason: String?) : HaEvent
}

/**
 * One Home Assistant WebSocket session, start to finish.
 *
 * The handshake is fixed by the server: it opens with auth_required, we answer with the
 * token, it answers auth_ok, and only then will it accept commands. Commands are matched
 * to their results by an id we allocate, so the three registry lists and the event
 * subscription can all be in flight at once.
 */
class HaSocket(private val client: HaClient, private val config: HaConfig) {

    private var nextId = 1

    /**
     * Runs until the socket closes or the coroutine is cancelled. Every outcome arrives
     * on [events]; nothing is thrown at the caller except cancellation.
     */
    suspend fun run(events: SendChannel<HaEvent>) {
        var registryEntities: List<HaEntityRegistryEntry>? = null
        var registryDevices: List<HaDeviceRegistryEntry>? = null
        var registryAreas: List<HaArea>? = null
        var registryFailed = false

        suspend fun emitAreasWhenComplete() {
            if (registryFailed) return
            val areas = registryAreas ?: return
            val entities = registryEntities ?: return
            val devices = registryDevices ?: return
            events.send(HaEvent.Areas(areas, buildEntityAreaIndex(entities, devices)))
        }

        try {
            client.http.webSocket(config.webSocketUrl) {
                var authenticated = false
                var subscriptionId = -1
                var areaId = -1
                var entityRegistryId = -1
                var deviceRegistryId = -1

                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    val message = runCatching {
                        client.json.parseToJsonElement(text).jsonObject
                    }.getOrNull() ?: continue

                    when (message["type"]?.jsonPrimitive?.content) {
                        "auth_required" -> {
                            send(Frame.Text(authMessage()))
                        }

                        "auth_invalid" -> {
                            val why = message["message"]?.jsonPrimitive?.content
                                ?: "Home Assistant rejected the access token."
                            events.send(HaEvent.AuthFailed(why))
                            return@webSocket
                        }

                        "auth_ok" -> {
                            authenticated = true
                            events.send(HaEvent.Connected)
                            subscriptionId = nextId++
                            send(Frame.Text(subscribeMessage(subscriptionId)))
                            areaId = nextId++
                            send(Frame.Text(command(areaId, "config/area_registry/list")))
                            entityRegistryId = nextId++
                            send(
                                Frame.Text(
                                    command(entityRegistryId, "config/entity_registry/list"),
                                ),
                            )
                            deviceRegistryId = nextId++
                            send(
                                Frame.Text(
                                    command(deviceRegistryId, "config/device_registry/list"),
                                ),
                            )
                        }

                        "event" -> {
                            if (!authenticated) continue
                            parseStateChanged(message)?.let { events.send(HaEvent.StateChanged(it)) }
                        }

                        "result" -> {
                            val id = runCatching { message["id"]?.jsonPrimitive?.int }
                                .getOrNull() ?: continue
                            val ok = message["success"]?.jsonPrimitive?.booleanOrNull == true
                            if (!ok) {
                                // Only the registry calls need admin rights. Losing them
                                // costs the Rooms tab, not the connection.
                                if (id == areaId || id == entityRegistryId ||
                                    id == deviceRegistryId
                                ) {
                                    if (!registryFailed) {
                                        registryFailed = true
                                        events.send(HaEvent.AreasUnavailable)
                                    }
                                }
                                continue
                            }
                            val result = message["result"] ?: continue
                            // A registry payload this build cannot decode costs the
                            // Rooms tab. Left to throw it costs the connection, and
                            // the reconnect loop then replays the same frame forever.
                            val decoded = runCatching {
                                when (id) {
                                    areaId -> registryAreas = client.json
                                        .decodeFromJsonElement<List<HaArea>>(result)

                                    entityRegistryId -> registryEntities = client.json
                                        .decodeFromJsonElement<List<HaEntityRegistryEntry>>(result)

                                    deviceRegistryId -> registryDevices = client.json
                                        .decodeFromJsonElement<List<HaDeviceRegistryEntry>>(result)

                                    else -> return@runCatching false
                                }
                                true
                            }.getOrElse {
                                if (!registryFailed) {
                                    registryFailed = true
                                    events.send(HaEvent.AreasUnavailable)
                                }
                                false
                            }
                            if (decoded) emitAreasWhenComplete()
                        }
                    }
                }
                events.send(HaEvent.Closed(null))
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            events.send(HaEvent.Closed(error.message))
        }
    }

    private fun authMessage(): String = client.json.encodeToString(
        buildJsonObject {
            put("type", JsonPrimitive("auth"))
            put("access_token", JsonPrimitive(config.token))
        },
    )

    private fun subscribeMessage(id: Int): String = client.json.encodeToString(
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("subscribe_events"))
            put("event_type", JsonPrimitive("state_changed"))
        },
    )

    private fun command(id: Int, type: String): String = client.json.encodeToString(
        buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive(type))
        },
    )

    /**
     * state_changed carries both the old and the new state, and a removed entity has a
     * null new_state. Dropping those keeps a deleted entity from resurfacing as a row
     * with no state at all.
     */
    private fun parseStateChanged(message: JsonObject): HaState? {
        val data = message["event"]?.jsonObject?.get("data")?.jsonObject ?: return null
        val newState = data["new_state"] ?: return null
        if (newState is kotlinx.serialization.json.JsonNull) return null
        return runCatching {
            client.json.decodeFromJsonElement<HaState>(newState)
        }.getOrNull()
    }
}
