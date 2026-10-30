package com.example.wakeupmusic.library.smart

import com.example.wakeupmusic.library.db.SortKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart playlist rules (F09). Stored as JSON, compiled to SQL. Only the fields and operators listed here can reach
 * the SQL, and every text value is a bound argument. Numbers are written as literals, because they are our own Long
 * values and that keeps SQLite's comparisons numeric.
 */
enum class Match { AND, OR }

enum class FieldType { TEXT, NUMBER, BOOL, TIME }

/** A field the rules may use. [column] is a fixed SQL expression over the aliases used by the track query. */
enum class Field(val key: String, val column: String, val type: FieldType) {
    TITLE("title", "t.title", FieldType.TEXT),
    ARTIST("artist", "ar.name", FieldType.TEXT),
    ALBUM("album", "al.name", FieldType.TEXT),
    GENRE("genre", "g.name", FieldType.TEXT),
    FOLDER("folder", "t.folder_path", FieldType.TEXT),
    RATING("rating", "t.rating", FieldType.NUMBER),
    FAVORITE("favorite", "t.favorite", FieldType.BOOL),
    DURATION_MS("duration_ms", "t.duration_ms", FieldType.NUMBER),
    YEAR("year", "t.year", FieldType.NUMBER),
    ADDED("added", "t.added_ms", FieldType.TIME),
    PLAY_COUNT("play_count", "COALESCE(ps.play_count, 0)", FieldType.NUMBER),
    LAST_PLAYED("last_played", "COALESCE(ps.last_played_ms, 0)", FieldType.TIME);

    companion object {
        fun byKey(key: String): Field = entries.first { it.key == key }
    }
}

enum class Op(val key: String, val sql: String) {
    EQ("eq", "="),
    NE("ne", "!="),
    GT("gt", ">"),
    GTE("gte", ">="),
    LT("lt", "<"),
    LTE("lte", "<="),
    CONTAINS("contains", "LIKE");

    companion object {
        fun byKey(key: String): Op = entries.first { it.key == key }
    }
}

sealed interface Value {
    data class Num(val v: Long) : Value
    data class Text(val v: String) : Value
    data class Flag(val on: Boolean) : Value

    /** A time relative to now, so "added in the last 30 days" stays true as days pass. */
    data class DaysAgo(val days: Int) : Value
}

data class Condition(val field: Field, val op: Op, val value: Value)

data class SmartRules(
    val match: Match = Match.AND,
    val conditions: List<Condition> = emptyList(),
    val sort: SortKey = SortKey.TITLE,
    val limit: Int = 200,
) {
    fun toJson(): String {
        val conds = JSONArray()
        for (c in conditions) {
            conds.put(
                JSONObject()
                    .put("field", c.field.key)
                    .put("op", c.op.key)
                    .put("value", valueToJson(c.value)),
            )
        }
        return JSONObject()
            .put("match", match.name)
            .put("sort", sort.name)
            .put("limit", limit)
            .put("conditions", conds)
            .toString()
    }

    companion object {
        /** Parses rules written by [toJson]. Returns null when the JSON is broken, so the caller can show it. */
        fun fromJson(json: String): SmartRules? = try {
            val o = JSONObject(json)
            val arr = o.getJSONArray("conditions")
            val conditions = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Condition(Field.byKey(c.getString("field")), Op.byKey(c.getString("op")), parseValue(c.get("value")))
            }
            SmartRules(
                match = Match.valueOf(o.optString("match", "AND")),
                conditions = conditions,
                sort = SortKey.valueOf(o.optString("sort", "TITLE")),
                limit = o.optInt("limit", 200),
            )
        } catch (e: Exception) {
            null
        }

        private fun valueToJson(v: Value): Any = when (v) {
            is Value.Num -> v.v
            is Value.Text -> v.v
            is Value.Flag -> v.on
            is Value.DaysAgo -> JSONObject().put("days_ago", v.days)
        }

        private fun parseValue(raw: Any?): Value = when (raw) {
            is Boolean -> Value.Flag(raw)
            is Number -> Value.Num(raw.toLong())
            is String -> Value.Text(raw)
            is JSONObject -> Value.DaysAgo(raw.getInt("days_ago"))
            else -> throw IllegalArgumentException("unsupported rule value")
        }
    }
}

/** Compiles rules to a WHERE clause. Throws IllegalArgumentException when a field and a value do not fit together. */
object SmartCompiler {
    private const val DAY_MS = 86_400_000L

    fun where(rules: SmartRules, args: MutableList<String>, nowMs: Long): String {
        if (rules.conditions.isEmpty()) return "1 = 1"
        val joiner = if (rules.match == Match.AND) " AND " else " OR "
        return rules.conditions.joinToString(joiner, prefix = "(", postfix = ")") { condition(it, args, nowMs) }
    }

    private fun condition(c: Condition, args: MutableList<String>, nowMs: Long): String {
        val column = c.field.column
        if (c.op == Op.CONTAINS) {
            require(c.field.type == FieldType.TEXT) { "contains needs a text field" }
            val text = (c.value as? Value.Text)?.v ?: throw IllegalArgumentException("contains needs text")
            args += "%" + escapeLike(text) + "%"
            return "$column LIKE ? ESCAPE '\\'"
        }
        val rhs: String = when (val v = c.value) {
            is Value.Num -> {
                require(c.field.type == FieldType.NUMBER || c.field.type == FieldType.TIME) { "number on ${c.field.key}" }
                v.v.toString()
            }
            is Value.DaysAgo -> {
                require(c.field.type == FieldType.TIME) { "days on ${c.field.key}" }
                (nowMs - v.days.toLong() * DAY_MS).toString()
            }
            is Value.Flag -> {
                require(c.field.type == FieldType.BOOL) { "flag on ${c.field.key}" }
                if (v.on) "1" else "0"
            }
            is Value.Text -> {
                require(c.field.type == FieldType.TEXT) { "text on ${c.field.key}" }
                args += v.v
                "?"
            }
        }
        return "$column ${c.op.sql} $rhs"
    }

    internal fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
