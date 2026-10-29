package io.polion.core.application

import io.polion.core.backup.PortableBackup
import io.polion.core.model.OwnerId
import io.polion.core.onboarding.OnboardingAction
import io.polion.core.onboarding.OnboardingGoal
import io.polion.core.onboarding.OnboardingPresentation
import io.polion.core.onboarding.OnboardingReducer
import io.polion.core.onboarding.OnboardingRepository
import io.polion.core.onboarding.OnboardingState
import io.polion.core.onboarding.OnboardingStep
import io.polion.core.ownership.Owner
import io.polion.core.storage.JvmSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingEngineTest {
    @Test
    fun emptyInstallCanFinishOfflineWithoutCreatingRecordsOrConsent() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            assertEquals(OnboardingState(), app.onboardingState())
            repeat(3) { app.updateOnboarding(OnboardingAction.Next) }
            assertTrue(app.updateOnboarding(OnboardingAction.Complete).completed)
            assertTrue(PortfolioApplication(db) { 1000L }.onboardingState()!!.completed)
            listOf("owners", "accounts", "portfolios", "targets", "ledger_activities").forEach {
                assertTrue(db.query("SELECT * FROM $it").isEmpty())
            }
            assertFalse(app.sourceConsent())
            assertFalse(app.priceSourceConsent())
        }
    }

    @Test
    fun answersStepAndPresentationSurviveReopenSkipResumeBackAndRestart() {
        JvmSqliteDatabase(":memory:").use { db ->
            var app = PortfolioApplication(db) { 1000L }
            app.updateOnboarding(OnboardingAction.SetPresentation(OnboardingPresentation.ASSISTED))
            app.updateOnboarding(OnboardingAction.Next)
            app.updateOnboarding(OnboardingAction.ToggleGoal(OnboardingGoal.LONG_TERM_HOLDER))
            app.updateOnboarding(OnboardingAction.Next)
            app.updateOnboarding(OnboardingAction.ToggleSource("bitcoin-public-wallet"))
            val skipped = app.updateOnboarding(OnboardingAction.Skip)
            app = PortfolioApplication(db) { 1000L }
            assertEquals(skipped, app.onboardingState())
            val resumed = app.updateOnboarding(OnboardingAction.Resume)
            assertFalse(resumed.skipped)
            assertEquals(OnboardingStep.SOURCES, resumed.step)
            assertEquals(setOf(OnboardingGoal.LONG_TERM_HOLDER), resumed.goals)
            assertEquals(setOf("bitcoin-public-wallet"), resumed.sourceIds)
            assertEquals(OnboardingPresentation.ASSISTED, resumed.presentation)
            assertEquals(OnboardingStep.GOALS, app.updateOnboarding(OnboardingAction.Back).step)
            assertEquals(OnboardingState(presentation = OnboardingPresentation.ASSISTED), app.updateOnboarding(OnboardingAction.Restart))
        }
    }

    @Test
    fun reducerBoundsAndCapabilityValidationDoNotCreateConnections() {
        val initial = OnboardingState()
        assertEquals(initial, OnboardingReducer.reduce(initial, OnboardingAction.Back))
        assertFails { OnboardingReducer.reduce(initial, OnboardingAction.Complete) }
        assertFails { OnboardingReducer.reduce(initial, OnboardingAction.ToggleSource("exchange-secret")) }
        val selected = OnboardingReducer.reduce(initial, OnboardingAction.ToggleGoal(OnboardingGoal.BALANCES))
        assertEquals(initial, OnboardingReducer.reduce(selected, OnboardingAction.ToggleGoal(OnboardingGoal.BALANCES)))
        val ready = initial.copy(step = OnboardingStep.READY)
        assertEquals(ready, OnboardingReducer.reduce(ready, OnboardingAction.Next))
        val completed = OnboardingReducer.reduce(ready, OnboardingAction.Complete)
        assertEquals(completed, OnboardingReducer.reduce(completed, OnboardingAction.Back))
        assertEquals(ready, OnboardingReducer.reduce(completed, OnboardingAction.Resume))
    }

    @Test
    fun populatedInstallEntersPortfolioUntilExplicitRestart() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.saveOwner(Owner(OwnerId("personal"), "Personal"))
            assertNull(app.onboardingState())
            assertTrue(db.query("SELECT * FROM application_settings").isEmpty())
            assertEquals(OnboardingState(), app.updateOnboarding(OnboardingAction.Restart))
            assertEquals(OnboardingState(), PortfolioApplication(db) { 1000L }.onboardingState())
        }
    }

    @Test
    fun malformedStateRecoversAndFutureVersionIsPreservedUntilExplicitRestart() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }

            fun save(value: String) {
                db.execute("INSERT OR REPLACE INTO application_settings VALUES (?, ?)", listOf(OnboardingRepository.KEY, value))
            }
            for (malformed in listOf("{broken", "{}", "[]", "null")) {
                save(malformed)
                assertEquals(OnboardingState(), app.onboardingState())
            }
            val future = "{\"version\":2,\"new-field\":\"keep\"}"
            save(future)
            assertNull(app.onboardingState())
            assertFails { app.updateOnboarding(OnboardingAction.Next) }
            assertEquals(
                future,
                db.query("SELECT value FROM application_settings WHERE key=?", listOf(OnboardingRepository.KEY)).single().single(),
            )
            assertEquals(OnboardingState(), app.updateOnboarding(OnboardingAction.Restart))
        }
    }

    @Test
    fun oversizedStateIsPreservedWithoutParsingUntilExplicitRestart() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            val payload = "x".repeat(9000)
            val states =
                listOf(
                    "{\"version\":2,\"payload\":\"$payload\"}",
                    "{\"payload\":\"$payload\",\"version\":2}",
                    payload,
                )
            for (saved in states) {
                db.execute("INSERT OR REPLACE INTO application_settings VALUES (?, ?)", listOf(OnboardingRepository.KEY, saved))
                assertNull(app.onboardingState())
                assertFails { app.updateOnboarding(OnboardingAction.Resume) }
                assertFails { app.updateOnboarding(OnboardingAction.Next) }
                assertEquals(
                    saved,
                    db.query("SELECT value FROM application_settings WHERE key=?", listOf(OnboardingRepository.KEY)).single().single(),
                )
                assertEquals(OnboardingState(), app.updateOnboarding(OnboardingAction.Restart))
            }
        }
    }

    @Test
    fun backupRestoresPartialSetupAndWipeStartsFresh() {
        JvmSqliteDatabase(":memory:").use { db ->
            val app = PortfolioApplication(db) { 1000L }
            app.updateOnboarding(OnboardingAction.Next)
            val expected = app.updateOnboarding(OnboardingAction.ToggleGoal(OnboardingGoal.OTHER))
            val backup = PortableBackup.discover(db)
            JvmSqliteDatabase(":memory:").use { staging ->
                val restored = PortfolioApplication(staging) { 1000L }
                backup.restoreToStaging(staging, backup.export(db))
                assertEquals(expected, restored.onboardingState())
                restored.wipe()
                assertEquals(OnboardingState(), restored.onboardingState())
            }
        }
    }
}
