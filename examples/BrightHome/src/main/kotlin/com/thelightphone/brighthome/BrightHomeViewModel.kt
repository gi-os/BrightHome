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

        val areaRows = world.areas.mapNotNull { area ->
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
            areas = areaRows,
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

    /** Rows for one area, or the loose entities when [areaId] is the unassigned bucket. */
    fun rowsForArea(areaId: String): List<EntityRow> {
        val entities = repository.entities.value.withOptimistic(repository.optimistic.value)
        val index = repository.entityArea.value
        return entities.values
            .filter { Domains.isInteresting(it.domain) && it.domain !in SCENE_DOMAINS }
            .filter {
                val area = index[it.entityId]
                if (areaId == UNASSIGNED_AREA) area == null else area == areaId
            }
            .sortedBy { it.friendlyName.lowercase() }
            .map { it.toRow() }
    }

    fun areaName(areaId: String): String =
        if (areaId == UNASSIGNED_AREA) uiState.value.unassigned?.name ?: "Devices"
        else repository.areas.value.firstOrNull { it.areaId == areaId }?.name ?: "Room"

    /** Everything worth pinning, grouped by room, for the favourites picker. */
    fun pickerGroups(): List<Pair<String, List<EntityRow>>> {
        val entities = repository.entities.value
        val index = repository.entityArea.value
        val areaNames = repository.areas.value.associate { it.areaId to it.name }

        return entities.values
            .filter { Domains.isInteresting(it.domain) }
            .groupBy { index[it.entityId]?.let { id -> areaNames[id] } ?: "No room" }
            .toList()
            .sortedWith(
                compareBy({ it.first == "No room" }, { it.first.lowercase() }),
            )
            .map { (name, members) ->
                name to members.sortedBy { it.friendlyName.lowercase() }.map { it.toRow() }
            }
    }

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
            repository.stop()
            repository.save(config)
            repository.start(viewModelScope)
        }
    }

    fun forget() {
        viewModelScope.launch { repository.forget() }
    }

    fun dismissToast() = repository.clearMessage()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        viewModelScope.launch {
            repository.load()
            repository.start(viewModelScope)
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        repository.stop()
    }

    override fun onAppPause() {
        super.onAppPause()
        // Nothing here runs in the background. A socket held open in a pocket costs
        // battery and buys nothing, because the screen is the only consumer.
        repository.stop()
    }

    override fun onCleared() {
        super.onCleared()
        repository.stop()
    }
}
