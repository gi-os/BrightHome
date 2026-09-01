package com.thelightphone.brighthome

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HomeTab(val title: String) {
    Favorites("Favorites"),
    Rooms("Rooms"),
    Scenes("Scenes"),
}

/** One row in any list. Everything the row needs to draw, nothing it does not. */
data class EntityRow(
    val entityId: String,
    val title: String,
    val subtitle: String,
    val kind: ControlKind,
    val isOn: Boolean,
    val unavailable: Boolean,
)

data class AreaRow(
    val areaId: String,
    val name: String,
    val onCount: Int,
    val total: Int,
)

data class BrightHomeUiState(
    val loading: Boolean = true,
    val paired: Boolean = false,
    val tab: HomeTab = HomeTab.Favorites,
    val status: ConnectionStatus = ConnectionStatus.Unpaired,
    /**
     * Null while everything is fine. The connection says its piece once, at the top, and
     * no screen repeats it underneath.
     */
    val statusLine: String? = null,
    val host: String = "",
    val favorites: List<EntityRow> = emptyList(),
    val areas: List<AreaRow> = emptyList(),
    val areasSupported: Boolean = true,
    val unassigned: AreaRow? = null,
    val scenes: List<EntityRow> = emptyList(),
    val toast: String? = null,
)

private const val UNASSIGNED_AREA = "__unassigned__"

/** Domains that belong on the Scenes tab rather than in a room. */
private val SCENE_DOMAINS = setOf("scene", "script", "button", "input_button", "automation")

class BrightHomeViewModel(
    dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    val repository = HaRepository(dataStore)

    private val tab = MutableStateFlow(HomeTab.Favorites)

    private data class Connection(
        val status: ConnectionStatus,
        val config: HaConfig?,
        val message: String?,
        val tab: HomeTab,
    )

    private data class World(
        val entities: Map<String, HaState>,
        val areas: List<HaArea>,
        val entityArea: Map<String, String>,
        val areasSupported: Boolean,
    )

    private val connection = combine(
        repository.status,
        repository.config,
        repository.message,
        tab,
    ) { status, config, message, currentTab -> Connection(status, config, message, currentTab) }

    private val world = combine(
        repository.entities,
        repository.optimistic,
        repository.areas,
        repository.entityArea,
        repository.areasSupported,
    ) { entities, optimistic, areas, entityArea, areasSupported ->
        World(entities.withOptimistic(optimistic), areas, entityArea, areasSupported)
    }

    val uiState: StateFlow<BrightHomeUiState> =
        combine(connection, world, repository.favorites) { conn, w, favorites ->
            build(conn, w, favorites)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BrightHomeUiState(),
        )

    private fun build(
        conn: Connection,
        world: World,
        favorites: List<String>,
    ): BrightHomeUiState {
        val paired = conn.config != null

        val favoriteRows = favorites.map { entityId ->
            world.entities[entityId]?.toRow() ?: missingRow(entityId)
        }

        val scenes = world.entities.values
            .filter { it.domain in SCENE_DOMAINS }
            .sortedBy { it.friendlyName.lowercase() }
            .map { it.toRow() }

        val controllable = world.entities.values.filter {
            Domains.isInteresting(it.domain) && it.domain !in SCENE_DOMAINS
        }

        val byArea = controllable.groupBy { world.entityArea[it.entityId] }

        val roomRows = world.areas.mapNotNull { area ->
            val members = byArea[area.areaId].orEmpty()
            if (members.isEmpty()) return@mapNotNull null
            AreaRow(
                areaId = area.areaId,
                name = area.name,
                onCount = members.count { Domains.isOn(it) },
                total = members.size,
            )
        }

        val loose = byArea[null].orEmpty()
        val unassigned = if (loose.isEmpty()) null else AreaRow(
            areaId = UNASSIGNED_AREA,
            name = if (world.areasSupported) "No room" else "All devices",
            onCount = loose.count { Domains.isOn(it) },
            total = loose.size,
        )

        return BrightHomeUiState(
            loading = paired && world.entities.isEmpty() &&
                conn.status is ConnectionStatus.Connecting,
            paired = paired,
            tab = conn.tab,
            status = conn.status,
            statusLine = statusLine(conn.status),
            host = conn.config?.displayHost.orEmpty(),
            favorites = favoriteRows,
            areas = roomRows,
            areasSupported = world.areasSupported,
            unassigned = unassigned,
            scenes = scenes,
            toast = conn.message,
        )
    }

    /**
     * Live is the silent case. Anything else gets exactly one line, and it says what is
     * wrong rather than that something is.
     */
    private fun statusLine(status: ConnectionStatus): String? = when (status) {
        is ConnectionStatus.Live -> null
        is ConnectionStatus.Unpaired -> null
        is ConnectionStatus.Connecting -> "Connecting…"
        is ConnectionStatus.Stale -> "Not live — showing the last known state"
        is ConnectionStatus.Failed -> status.reason
    }

    private fun HaState.toRow(): EntityRow = EntityRow(
        entityId = entityId,
        title = friendlyName,
        subtitle = StateSummary.secondaryLine(this),
        kind = Domains.controlKind(domain),
        isOn = Domains.isOn(this),
        unavailable = isUnavailable,
    )

    /** A favourite whose entity is gone still gets a row, so it can be removed. */
    private fun missingRow(entityId: String): EntityRow = EntityRow(
        entityId = entityId,
        title = entityId.substringAfter('.').replace('_', ' ')
            .replaceFirstChar { it.uppercase() },
        subtitle = "Not in Home Assistant",
        kind = ControlKind.ReadOnly,
        isOn = false,
        unavailable = true,
    )

    /**
     * Rows for one area, or the loose entities when [areaId] is the unassigned bucket.
     *
     * This is a flow rather than a value read off uiState. BrightHomeUiState summarises a
     * room as a name and an on-count, so a brightness change or a new media title leaves
     * it equal, the StateFlow conflates the emission away, and a screen deriving its rows
     * from it would sit there showing "On · 20%" while the lamp was at 80.
     */
    fun areaRows(areaId: String): StateFlow<List<EntityRow>> = combine(
        repository.entities,
        repository.optimistic,
        repository.entityArea,
    ) { entities, optimistic, index ->
        entities.withOptimistic(optimistic).values
            .filter { Domains.isInteresting(it.domain) && it.domain !in SCENE_DOMAINS }
            .filter {
                val area = index[it.entityId]
                if (areaId == UNASSIGNED_AREA) area == null else area == areaId
            }
            .sortedBy { it.friendlyName.lowercase() }
            .map { it.toRow() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun areaName(areaId: String): String = when {
        areaId != UNASSIGNED_AREA ->
            repository.areas.value.firstOrNull { it.areaId == areaId }?.name ?: "Room"

        // Same wording the Rooms list used for the bucket, so the title does not
        // rename itself on the way in.
        repository.areasSupported.value -> "No room"
        else -> "All devices"
    }

    /** Everything worth pinning, grouped by room, for the favourites picker. */
    val pickerGroups: StateFlow<List<Pair<String, List<EntityRow>>>> = combine(
        repository.entities,
        repository.entityArea,
        repository.areas,
    ) { entities, index, areas ->
        val areaNames = areas.associate { it.areaId to it.name }
        entities.values
            .filter { Domains.isInteresting(it.domain) }
            .groupBy { index[it.entityId]?.let { id -> areaNames[id] } ?: "No room" }
            .toList()
            .sortedWith(compareBy({ it.first == "No room" }, { it.first.lowercase() }))
            .map { (name, members) ->
                name to members.sortedBy { it.friendlyName.lowercase() }.map { it.toRow() }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectTab(next: HomeTab) {
        if (tab.value == next) {
            refresh()
            return
        }
        tab.value = next
    }

    fun act(entityId: String) = repository.act(viewModelScope, entityId)

    fun refresh() = repository.refresh(viewModelScope)

    fun toggleFavorite(entityId: String) {
        viewModelScope.launch { repository.toggleFavorite(entityId) }
    }

    fun setFavorites(ids: List<String>) {
        viewModelScope.launch { repository.setFavorites(ids) }
    }

    fun pair(config: HaConfig) {
        viewModelScope.launch {
            repository.stopAndJoin()
            repository.save(config)
            repository.start(viewModelScope)
        }
    }

    fun forget() {
        viewModelScope.launch { repository.forget() }
    }

    fun dismissToast() = repository.clearMessage()

    /**
     * Opening a sub-screen calls onScreenHide, because LightOS hides the screen
     * underneath whatever it pushes. Tearing the connection down there is what made
     * every room, the settings refresh and every tap outside Favorites silently do
     * nothing — the client was already closed by the time the user arrived. The
     * connection follows the *app* lifecycle, so only resume and pause move it.
     */
    fun resume() {
        viewModelScope.launch {
            repository.load()
            repository.start(viewModelScope)
        }
    }

    /**
     * Nothing here runs in the background. A socket held open in a pocket costs battery
     * and buys nothing, because the screen is the only consumer.
     */
    fun pause() {
        repository.stop()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        resume()
    }

    override fun onAppPause() {
        super.onAppPause()
        pause()
    }

    override fun onCleared() {
        super.onCleared()
        repository.stop()
    }
}
