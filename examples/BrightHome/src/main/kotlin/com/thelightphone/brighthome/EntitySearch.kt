package com.thelightphone.brighthome

/**
 * Finding one thing in a house full of entities.
 *
 * A Home Assistant instance of any age has hundreds of them, most named by an
 * integration rather than by a person, so a plain "contains" match returns a wall. The
 * ranking below puts what you meant at the top: a name that starts with what you typed
 * beats one that merely contains it, a whole-word hit beats a mid-word one, and the room
 * is searchable too because "kitchen" is how people think about a light.
 */
object EntitySearch {

    private const val RANK_NAME_PREFIX = 0
    private const val RANK_WORD_PREFIX = 1
    private const val RANK_NAME_CONTAINS = 2
    private const val RANK_AREA = 3
    private const val RANK_ID = 4

    data class Hit(val state: HaState, val area: String?, private val rank: Int) {
        internal fun order(): Int = rank
    }

    fun search(
        query: String,
        entities: Collection<HaState>,
        areaOf: (String) -> String?,
        limit: Int = 60,
    ): List<Hit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>()
        for (state in entities) {
            if (!Domains.isInteresting(state.domain)) continue
            val area = areaOf(state.entityId)
            val rank = rankOf(needle, state, area) ?: continue
            hits.add(Hit(state, area, rank))
        }

        return hits
            .sortedWith(
                compareBy(
                    { it.order() },
                    { it.state.friendlyName.lowercase() },
                ),
            )
            .take(limit)
    }

    private fun rankOf(needle: String, state: HaState, area: String?): Int? {
        val name = state.friendlyName.lowercase()
        if (name.startsWith(needle)) return RANK_NAME_PREFIX
        if (startsAWord(name, needle)) return RANK_WORD_PREFIX
        if (name.contains(needle)) return RANK_NAME_CONTAINS
        if (area != null && area.lowercase().contains(needle)) return RANK_AREA
        // The entity_id is the last resort, and it is genuinely useful: it is the only
        // handle some integrations give a thing.
        if (state.entityId.lowercase().contains(needle)) return RANK_ID
        return null
    }

    /** "desk" should find "Office desk lamp" ahead of "Sideboard desk". */
    private fun startsAWord(haystack: String, needle: String): Boolean {
        var from = haystack.indexOf(needle)
        while (from > 0) {
            if (!haystack[from - 1].isLetterOrDigit()) return true
            from = haystack.indexOf(needle, from + 1)
        }
        return false
    }
}
