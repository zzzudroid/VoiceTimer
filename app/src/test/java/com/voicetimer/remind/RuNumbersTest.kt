package com.voicetimer.remind

import org.junit.Assert.assertEquals
import org.junit.Test

// Разбор русских числительных, в т.ч. составных.
class RuNumbersTest {

    @Test fun singleWords() {
        assertEquals(9, RuNumbers.parseCompound("девять"))
        assertEquals(19, RuNumbers.parseCompound("девятнадцать"))
        assertEquals(20, RuNumbers.parseCompound("двадцать"))
        assertEquals(0, RuNumbers.parseCompound("ноль"))
    }

    @Test fun compound() {
        assertEquals(25, RuNumbers.parseCompound("двадцать пять"))
        assertEquals(49, RuNumbers.parseCompound("сорок девять"))
    }

    @Test fun digits() {
        assertEquals(7, RuNumbers.parseCompound("7"))
        assertEquals(30, RuNumbers.parseCompound("30"))
    }

    @Test fun unknownReturnsNull() {
        assertEquals(null, RuNumbers.parseCompound("абракадабра"))
    }
}
