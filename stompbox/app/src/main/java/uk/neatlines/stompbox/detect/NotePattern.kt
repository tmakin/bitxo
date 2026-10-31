package uk.neatlines.stompbox.detect

/**
 * A sequence of notes that advances by one on every tap, e.g. "E2" (one note) or "E2 B1"
 * (alternating root / fifth). Accepts note names (C, C#, Db ... B with an octave, where C4 is
 * middle C and E2 is MIDI 40) or raw MIDI numbers, separated by spaces or commas.
 */
class NotePattern private constructor(val notes: List<Int>) {
    private var index = 0

    /** Returns the next note and advances. Safe to call from any thread. */
    @Synchronized
    fun next(): Int {
        val n = notes[index % notes.size]
        index = (index + 1) % notes.size
        return n
    }

    @Synchronized
    fun restart() {
        index = 0
    }

    companion object {
        private val NAME = Regex("^([A-Ga-g])([#b]?)(-?\\d)$")
        private val PITCH_CLASS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** MIDI note number to a name such as "E2". */
        fun name(midi: Int): String = NAMES[midi.mod(12)] + (midi.floorDiv(12) - 1)

        /** Parses one note token, returning a MIDI note number or null if invalid. */
        fun parseNote(token: String): Int? {
            token.toIntOrNull()?.let { return it.takeIf { n -> n in 0..127 } }
            val m = NAME.matchEntire(token.trim()) ?: return null
            val (letter, accidental, octave) = m.destructured
            var pc = PITCH_CLASS.getValue(letter[0].uppercaseChar())
            if (accidental == "#") pc++ else if (accidental == "b") pc--
            val midi = (octave.toInt() + 1) * 12 + pc
            return midi.takeIf { it in 0..127 }
        }

        /** Parses a pattern string. Returns null if it is empty or any token is invalid. */
        fun parse(text: String): NotePattern? {
            val tokens = text.split(' ', ',', '\n', '\t').filter { it.isNotBlank() }
            if (tokens.isEmpty()) return null
            val notes = tokens.map { parseNote(it) ?: return null }
            return NotePattern(notes)
        }
    }
}
