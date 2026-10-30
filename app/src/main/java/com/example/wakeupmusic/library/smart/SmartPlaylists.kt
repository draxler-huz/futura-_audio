package com.example.wakeupmusic.library.smart

import com.example.wakeupmusic.library.db.SortKey

/** The six built-in smart playlists from F09. Stored in the playlists table, so they show up next to user playlists. */
object SmartPlaylists {

    data class Builtin(val key: String, val name: String, val rules: SmartRules)

    fun builtins(): List<Builtin> = listOf(
        Builtin("recently_added", "Recently Added", SmartRules(Match.AND, emptyList(), SortKey.ADDED, 200)),
        Builtin(
            "most_played", "Most Played",
            SmartRules(Match.AND, listOf(Condition(Field.PLAY_COUNT, Op.GT, Value.Num(0))), SortKey.PLAY_COUNT, 200),
        ),
        Builtin(
            "recently_played", "Recently Played",
            SmartRules(Match.AND, listOf(Condition(Field.LAST_PLAYED, Op.GT, Value.Num(0))), SortKey.LAST_PLAYED, 200),
        ),
        Builtin(
            "top_rated", "Top Rated",
            SmartRules(Match.AND, listOf(Condition(Field.RATING, Op.GTE, Value.Num(4))), SortKey.RATING, 200),
        ),
        Builtin(
            "never_played", "Never Played",
            SmartRules(Match.AND, listOf(Condition(Field.PLAY_COUNT, Op.EQ, Value.Num(0))), SortKey.ADDED, 500),
        ),
        Builtin(
            "favorites", "Favorites",
            SmartRules(Match.AND, listOf(Condition(Field.FAVORITE, Op.EQ, Value.Flag(true))), SortKey.ARTIST, 1000),
        ),
    )
}
