package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvSessionStoreTest {
    @Test
    fun csvEscapingQuotesEveryFieldAndDoublesEmbeddedQuotes() {
        assertEquals("\"plain\"", csvEscape("plain"))
        assertEquals("\"a,b\"", csvEscape("a,b"))
        assertEquals("\"say \"\"hello\"\"\"", csvEscape("say \"hello\""))
        assertEquals("\"line1\r\nline2\"", csvEscape("line1\r\nline2"))
    }
}
