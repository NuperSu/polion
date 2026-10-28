package io.polion.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LockPasswordTest {
    @Test
    fun verifierIsSaltedAndRejectsWrongPasswordsAndCorruption() {
        val first = LockPassword.create("correct password")
        val second = LockPassword.create("correct password")
        assertNotEquals(first, second)
        assertTrue(LockPassword.verify("correct password", first))
        assertFalse(LockPassword.verify("wrong password", first))
        assertFalse(LockPassword.verify("correct password", "broken"))
    }
}
