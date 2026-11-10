package io.valroot.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LockRecoveryTest {
    @Test fun forgottenPasswordCanEraseLocalDataAndRemoveTheLockAfterTypedConfirmation() {
        ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
            var erased = 0
            val host =
                object : PlatformActions by delegate {
                    override fun eraseAndRemoveLock(onResult: (String) -> Unit) {
                        erased++
                        onResult("Local data erased and privacy lock removed")
                    }
                }
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent { ValrootTheme(AppearanceTheme.DARK) { LockScreen(host) } }
                onNodeWithTag("lock-forgot").performClick()
                onNodeWithTag("lock-erase-confirm").assertIsNotEnabled()
                onNodeWithText("Type ERASE to confirm").performTextInput("ERASE")
                onNodeWithTag("lock-erase-confirm").performClick()
                assertEquals(1, erased)
            }
        }
    }

    @Test fun fingerprintButtonAppearsOnlyWhenEnabledAndReportsFailures() {
        ModelWorkspaceRestorationTest.RestorationHost().use { delegate ->
            var attempts = 0
            val host =
                object : PlatformActions by delegate {
                    override fun biometricUnlock() = BiometricUnlock.ON

                    override fun unlockWithBiometrics(onResult: (UnlockResult) -> Unit) {
                        attempts++
                        onResult(UnlockResult(false, "Fingerprint not recognized"))
                    }
                }
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent { ValrootTheme(AppearanceTheme.DARK) { LockScreen(host) } }
                onNodeWithTag("lock-biometric").performClick()
                onNodeWithText("Fingerprint not recognized").assertExists()
                assertEquals(1, attempts)
            }
        }
        ModelWorkspaceRestorationTest.RestorationHost().use { host ->
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent { ValrootTheme(AppearanceTheme.DARK) { LockScreen(host) } }
                onNodeWithTag("lock-biometric").assertDoesNotExist()
            }
        }
    }
}
