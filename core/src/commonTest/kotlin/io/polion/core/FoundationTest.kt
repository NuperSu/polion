package io.polion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundationTest {
    @Test
    fun applicationExplainsWatchOnlyBoundary() {
        assertEquals("Polion", ApplicationInfo.NAME)
        assertTrue(ApplicationInfo.PURPOSE.contains("watch-only"))
        assertTrue(ApplicationInfo.PRIVACY_NOTICE.contains("Never enter seed phrases or private keys"))
    }

    @Test
    fun loggerReceivesOnlyDefinedEvents() {
        val events = mutableListOf<LogEvent>()
        val logger = Logger(events::add)
        logger.log(LogEvent.APPLICATION_STARTED)
        assertEquals(listOf(LogEvent.APPLICATION_STARTED), events)
    }
}
