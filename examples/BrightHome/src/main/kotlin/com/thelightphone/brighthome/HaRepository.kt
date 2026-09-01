package com.thelightphone.brighthome

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** How long a guess stands before BrightHome goes and asks what actually happened. */
private const val CONFIRM_TIMEOUT_MILLIS = 6_000L

object BrightHomePreferences {
    val CONFIG = stringPreferencesKey("ha_config")
    val FAVORITES = stringPreferencesKey("favorites")
}

sealed interface ConnectionStatus {
    data object Unpaired : ConnectionStatus
    data object Connecting : ConnectionStatus

    /** REST snapshot in hand and the socket is live. */
    data object Live : ConnectionStatus

    /** Snapshot in hand, socket down — the rows are real but they will not move. */
    data class Stale(val reason: String?) : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}

/**
 * Owns the connection and the merged picture of the house.
 *
 * Two sources feed one map: the REST snapshot fills it on open, the WebSocket patches it
 * afterwards. A tap writes a third layer on top — the optimistic state — which the next
 * real update for that entity clears. Over a tunnel the round trip is long enough that
 * without it a light appears not to have responded.
 */
class HaRepository(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _config = MutableStateFlow<HaConfig?>(null)
    val config: StateFlow<HaConfig?> = _config.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaState>>(emptyMap())
    val entities: StateFlow<Map<String, HaState>> = _entities.asStateFlow()

    private val _optimistic = MutableStateFlow<Map<String, String>>(emptyMap())
    val optimistic: StateFlow<Map<String, String>> = _optimistic.asStateFlow()

    private val _areas = MutableStateFlow<List<HaArea>>(emptyList())
    val areas: StateFlow<List<HaArea>> = _areas.asStateFlow()

    private val _entityArea = MutableStateFlow<Map<String, String>>(emptyMap())
    val entityArea: StateFlow<Map<String, String>> = _entityArea.asStateFlow()

    private val _areasSupported = MutableStateFlow(true)
    val areasSupported: StateFlow<Boolean> = _areasSupported.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unpaired)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var client: HaClient? = null
    private var connectionJob: Job? = null

    /**
     * Stamped onto each connection attempt. Cancellation is not instant, so without
     * this a job that is still unwinding can write its last gasp over the state a
     * freshly started job has already published — which is how "Failed" gets stuck on
     * screen while a perfectly good socket is running underneath it.
     */
    private var generation = 0

    /** One pending optimistic-clear timer per entity, so a second tap cancels the first. */
    private val clearJobs = mutableMapOf<String, Job>()

    private var loaded = false

    /**
     * Reads what was stored last time, once.
     *
     * Re-reading on every resume raced the writes it was about to overwrite: star an
     * entity and press back and the screen-show reload could hand back the pre-toggle
     * list. Memory is authoritative once it has been populated.
     */
    suspend fun load() {
        if (loaded) return
        val prefs = dataStore.data.first()
        _favorites.value = decodeFavorites(prefs[BrightHomePreferences.FAVORITES])
        val stored = prefs[BrightHomePreferences.CONFIG]?.let { ConfigCodec.decode(it) }
        _config.value = stored
        loaded = true
        if (stored == null) _status.value = ConnectionStatus.Unpaired
    }

    suspend fun save(config: HaConfig) {
        dataStore.edit { prefs ->
            prefs[BrightHomePreferences.CONFIG] = ConfigCodec.encode(config)
        }
        _config.value = config
    }

    suspend fun forget() {
        stop()
        dataStore.edit { prefs ->
            prefs.remove(BrightHomePreferences.CONFIG)
            prefs.remove(BrightHomePreferences.FAVORITES)
        }
        _config.value = null
        _entities.value = emptyMap()
        _areas.value = emptyList()
        _entityArea.value = emptyMap()
        _favorites.value = emptyList()
        _status.value = ConnectionStatus.Unpaired
        loaded = true
    }

    suspend fun setFavorites(ids: List<String>) {
        _favorites.value = ids
        dataStore.edit { prefs ->
            prefs[BrightHomePreferences.FAVORITES] = json.encodeToString(StoredFavorites(ids))
        }
    }

    suspend fun toggleFavorite(entityId: String) {
        val current = _favorites.value
        setFavorites(
            if (entityId in current) current - entityId else current + entityId,
        )
    }

    /**
     * Opens the connection. The snapshot lands first because it is one request and fills
     * every row; the socket follows and keeps them moving.
     */
    fun start(scope: CoroutineScope) {
        val config = _config.value ?: run {
            _status.value = ConnectionStatus.Unpaired
            return
        }
        if (connectionJob?.isActive == true) return

        val myGeneration = ++generation
        fun current() = myGeneration == generation

        _status.value = ConnectionStatus.Connecting
        client?.close()
        val haClient = HaClient(config)
        client = haClient

        connectionJob = scope.launch(Dispatchers.IO) {
            try {
                haClient.states().fold(
                    onSuccess = { snapshot ->
                        if (!current()) return@launch
                        _entities.value = snapshot.associateBy { it.entityId }
                        _status.value = ConnectionStatus.Stale(null)
                    },
                    onFailure = { error ->
                        if (!current()) return@launch
                        _status.value = ConnectionStatus.Failed(
                            error.message ?: "Could not reach ${config.displayHost}.",
                        )
                        return@launch
                    },
                )

                var backoffMillis = 1_000L
                while (isActive && current()) {
                    val events = Channel<HaEvent>(Channel.BUFFERED)
                    var reachedLive = false
                    val pump = launch {
                        for (event in events) {
                            if (event is HaEvent.Connected) reachedLive = true
                            // handle() must never throw: this pump is a child of the
                            // connection job, so an exception here would take the whole
                            // connection down without saying why.
                            runCatching { if (current()) handle(event) }
                        }
                    }
                    HaSocket(haClient, config).run(events)
                    events.close()
                    pump.join()

                    if (!isActive || !current()) break
                    if (_status.value is ConnectionStatus.Failed) break

                    // The backoff belongs to the socket, so only the socket may reset
                    // it. Resetting on a successful REST poll instead meant that an
                    // instance whose REST works and whose upgrade does not — Access
                    // refusing the upgrade, a proxy without the Upgrade header — sat in
                    // a 1Hz loop re-downloading the entire state snapshot forever.
                    backoffMillis =
                        if (reachedLive) 1_000L
                        else (backoffMillis * 2).coerceAtMost(30_000L)

                    delay(backoffMillis)
                    if (!current()) break

                    haClient.states().onSuccess { snapshot ->
                        if (current()) _entities.value = snapshot.associateBy { it.entityId }
                    }
                }
            } finally {
                // Every exit path closes the client, including the two early returns
                // above. Leaving it open leaked an OkHttp dispatcher and pool per
                // failed foreground.
                haClient.close()
                if (current()) client = null
            }
        }
    }

    /**
     * Bumping the generation first is what makes this safe to call immediately before
     * start(): the outgoing job may take a while to notice it has been cancelled, and
     * from this moment nothing it writes is allowed to land.
     */
    fun stop() {
        generation++
        connectionJob?.cancel()
        connectionJob = null
        clearJobs.values.forEach { it.cancel() }
        clearJobs.clear()
        _optimistic.value = emptyMap()
        client?.close()
        client = null
        if (_status.value !is ConnectionStatus.Unpaired) {
            _status.value = ConnectionStatus.Stale(null)
        }
    }

    /** stop(), but waits for the old connection to actually finish unwinding. */
    suspend fun stopAndJoin() {
        val job = connectionJob
        stop()
        job?.join()
    }

    private fun handle(event: HaEvent) {
        when (event) {
            is HaEvent.Connected -> {
                _status.value = ConnectionStatus.Live
            }

            is HaEvent.StateChanged -> {
                _entities.update { current -> current + (event.state.entityId to event.state) }
                // The truth arrived; the guess is no longer needed.
                _optimistic.update { current -> current - event.state.entityId }
            }

            is HaEvent.Areas -> {
                _areas.value = event.areas.sortedBy { it.name.lowercase() }
                _entityArea.value = event.entityArea
                _areasSupported.value = true
            }

            is HaEvent.AreasUnavailable -> {
                _areasSupported.value = false
            }

            is HaEvent.AuthFailed -> {
                _status.value = ConnectionStatus.Failed(event.message)
            }

            is HaEvent.Closed -> {
                if (_status.value is ConnectionStatus.Live) {
                    _status.value = ConnectionStatus.Stale(event.reason)
                }
            }
        }
    }

    /**
     * Flips an entity. The row changes under the finger; the service call follows. If
     * the call fails the guess is dropped and the real state snaps back.
     */
    fun act(scope: CoroutineScope, entityId: String) {
        val state = effectiveState(entityId) ?: return
        val call = Domains.toggleService(state) ?: return
        val haClient = client

        val predicted = Domains.optimisticState(state)
        if (predicted != null) {
            _optimistic.update { current -> current + (entityId to predicted) }
        }

        if (haClient == null) {
            retract(entityId, predicted)
            _message.value = "Not connected."
            return
        }

        // A second tap supersedes the first, so the first tap's timer must not be
        // allowed to fire and retract the second tap's guess six seconds early.
        clearJobs.remove(entityId)?.cancel()
        clearJobs[entityId] = scope.launch(Dispatchers.IO) {
            haClient.callService(call).fold(
                onSuccess = {
                    if (Domains.controlKind(state.domain) == ControlKind.Momentary) {
                        _message.value = "${state.friendlyName} — done"
                    }
                    // A dead socket never sends the confirming state_changed, so the
                    // guess would otherwise stay on screen forever. Rather than snap
                    // back to a truth that is now stale, ask for the real one.
                    delay(CONFIRM_TIMEOUT_MILLIS)
                    haClient.states().onSuccess { snapshot ->
                        _entities.value = snapshot.associateBy { it.entityId }
                    }
                    retract(entityId, predicted)
                },
                onFailure = { error ->
                    retract(entityId, predicted)
                    _message.value = error.message ?: "That did not go through."
                },
            )
            clearJobs.remove(entityId)
        }
    }

    /**
     * Removes a guess only if it is still the guess this call made. Without the
     * comparison, a slow first tap retracts a fast second tap's prediction.
     */
    private fun retract(entityId: String, predicted: String?) {
        if (predicted == null) return
        _optimistic.update { current ->
            if (current[entityId] == predicted) current - entityId else current
        }
    }

    /** Re-pulls the snapshot without tearing the socket down. */
    fun refresh(scope: CoroutineScope) {
        val haClient = client ?: return
        scope.launch(Dispatchers.IO) {
            haClient.states().fold(
                onSuccess = { snapshot ->
                    _entities.value = snapshot.associateBy { it.entityId }
                    _optimistic.value = emptyMap()
                },
                onFailure = { error ->
                    _message.value = error.message ?: "Refresh failed."
                },
            )
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** The state a row should render: the optimistic guess if one is pending, else the truth. */
    fun effectiveState(entityId: String): HaState? {
        val real = _entities.value[entityId] ?: return null
        val pending = _optimistic.value[entityId] ?: return real
        return real.copy(state = pending)
    }

    private fun decodeFavorites(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<StoredFavorites>(raw).ids }.getOrDefault(emptyList())
    }
}

/** Applies the pending guesses to a whole map in one pass, for the list screens. */
fun Map<String, HaState>.withOptimistic(pending: Map<String, String>): Map<String, HaState> {
    if (pending.isEmpty()) return this
    val patched = toMutableMap()
    for ((entityId, state) in pending) {
        val real = patched[entityId] ?: continue
        patched[entityId] = real.copy(state = state)
    }
    return patched
}
