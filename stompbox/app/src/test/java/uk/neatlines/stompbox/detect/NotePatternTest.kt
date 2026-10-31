package uk.neatlines.stompbox.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotePatternTest {
    @Test
    fun parsesNoteNames() {
        assertEquals(40, NotePattern.parseNote("E2"))
        assertEquals(60, NotePattern.parseNote("C4"))
        assertEquals(42, NotePattern.parseNote("F#2"))
        assertEquals(34, NotePattern.parseNote("Bb1"))
        assertEquals(33, NotePattern.parseNote("a1"))
        assertEquals(36, NotePattern.parseNote("36"))
        assertNull(NotePattern.parseNote("H2"))
        assertNull(NotePattern.parseNote("200"))
    }

    @Test
    fun cyclesThroughNotes() {
        val p = NotePattern.parse("E2, B1  A1")!!
        assertEquals(listOf(40, 35, 33, 40, 35), List(5) { p.next() })
        p.restart()
        assertEquals(40, p.next())
    }

    @Test
    fun namesRoundTrip() {
        for (n in 0..127) assertEquals(n, NotePattern.parseNote(NotePattern.name(n)))
        assertEquals("E2", NotePattern.name(40))
    }

    @Test
    fun rejectsBadPatterns() {
        assertNull(NotePattern.parse("   "))
        assertNull(NotePattern.parse("E2 X9"))
    }
}
