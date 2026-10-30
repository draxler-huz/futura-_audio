package com.example.wakeupmusic.library.smart

import com.example.wakeupmusic.library.db.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCompilerTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test
    fun addedInTheLast30DaysAndRatedFourOrMoreCompilesToLiterals() {
        val rules = SmartRules(
            Match.AND,
            listOf(
                Condition(Field.ADDED, Op.GTE, Value.DaysAgo(30)),
                Condition(Field.RATING, Op.GTE, Value.Num(4)),
            ),
            SortKey.ADDED,
            50,
        )
        val args = ArrayList<String>()
        val sql = SmartCompiler.where(rules, args, now)
        assertEquals("(t.added_ms >= ${now - 30 * day} AND t.rating >= 4)", sql)
        assertTrue(args.isEmpty())
    }

    @Test
    fun textValuesAreBoundNotInlined() {
        val args = ArrayList<String>()
        val sql = SmartCompiler.where(
            SmartRules(Match.AND, listOf(Condition(Field.ARTIST, Op.CONTAINS, Value.Text("it's 100%")))),
            args,
            now,
        )
        assertEquals("(ar.name LIKE ? ESCAPE '\\')", sql)
        assertEquals(listOf("%it's 100\\%%"), args)
    }

    @Test
    fun orJoinsConditions() {
        val args = ArrayList<String>()
        val sql = SmartCompiler.where(
            SmartRules(
                Match.OR,
                listOf(
                    Condition(Field.FAVORITE, Op.EQ, Value.Flag(true)),
                    Condition(Field.PLAY_COUNT, Op.EQ, Value.Num(0)),
                ),
            ),
            args,
            now,
        )
        assertEquals("(t.favorite = 1 OR COALESCE(ps.play_count, 0) = 0)", sql)
    }

    @Test
    fun emptyConditionsMatchEverything() {
        assertEquals("1 = 1", SmartCompiler.where(SmartRules(), ArrayList(), now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun numberOnATextFieldIsRejected() {
        SmartCompiler.where(SmartRules(conditions = listOf(Condition(Field.TITLE, Op.EQ, Value.Num(1)))), ArrayList(), now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun daysAgoOnANumberFieldIsRejected() {
        SmartCompiler.where(SmartRules(conditions = listOf(Condition(Field.RATING, Op.GT, Value.DaysAgo(3)))), ArrayList(), now)
    }
}
