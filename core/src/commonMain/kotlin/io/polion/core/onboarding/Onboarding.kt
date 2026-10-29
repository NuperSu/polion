package io.polion.core.onboarding

import io.polion.core.sources.SupportedSources
import io.polion.core.storage.SqliteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class OnboardingStep { WELCOME, GOALS, SOURCES, READY }

enum class OnboardingGoal { LONG_TERM_HOLDER, ACTIVE_TRADER, DEFI_USER, BALANCES, OTHER }

enum class OnboardingPresentation { FORM, ASSISTED }

/** Display preferences only: these answers never configure sources or grant provider consent. */
data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val goals: Set<OnboardingGoal> = emptySet(),
    val sourceIds: Set<String> = emptySet(),
    val presentation: OnboardingPresentation = OnboardingPresentation.FORM,
    val completed: Boolean = false,
    val skipped: Boolean = false,
)

sealed interface OnboardingAction {
    data object Next : OnboardingAction

    data object Back : OnboardingAction

    data class ToggleGoal(
        val goal: OnboardingGoal,
    ) : OnboardingAction

    data class ToggleSource(
        val sourceId: String,
    ) : OnboardingAction

    data class SetPresentation(
        val presentation: OnboardingPresentation,
    ) : OnboardingAction

    data object Skip : OnboardingAction

    data object Resume : OnboardingAction

    data object Restart : OnboardingAction

    data object Complete : OnboardingAction
}

/** Pure transitions shared by form and assisted presentations. All answers are optional. */
object OnboardingReducer {
    fun reduce(
        state: OnboardingState,
        action: OnboardingAction,
    ): OnboardingState {
        if (action == OnboardingAction.Restart) return OnboardingState(presentation = state.presentation)
        if (action == OnboardingAction.Resume) return state.copy(completed = false, skipped = false)
        if (state.completed || state.skipped) return state
        return when (action) {
            OnboardingAction.Next -> state.copy(step = OnboardingStep.entries[(state.step.ordinal + 1).coerceAtMost(3)])
            OnboardingAction.Back -> state.copy(step = OnboardingStep.entries[(state.step.ordinal - 1).coerceAtLeast(0)])
            is OnboardingAction.ToggleGoal -> state.copy(goals = toggle(state.goals, action.goal))
            is OnboardingAction.ToggleSource -> {
                require(SupportedSources.choices.any { it.id == action.sourceId }) { "Unsupported onboarding source" }
                state.copy(sourceIds = toggle(state.sourceIds, action.sourceId))
            }
            is OnboardingAction.SetPresentation -> state.copy(presentation = action.presentation)
            OnboardingAction.Skip -> state.copy(skipped = true)
            OnboardingAction.Complete -> {
                require(state.step == OnboardingStep.READY) { "Finish the setup steps before completing" }
                state.copy(completed = true)
            }
            OnboardingAction.Resume, OnboardingAction.Restart -> state
        }
    }

    private fun <T> toggle(
        values: Set<T>,
        value: T,
    ): Set<T> = if (value in values) values - value else values + value
}

/** The settings row participates in the application's existing portable backup and wipe paths. */
class OnboardingRepository(
    private val db: SqliteDatabase,
) {
    fun load(): OnboardingState? {
        val raw = saved()
        if (raw != null) {
            // Future versions and oversized states remain intact until an explicit restart.
            if (requiresExplicitRestart(raw)) return null
            decode(raw)?.let { return it }
        }
        return if (hasPortfolioData()) null else OnboardingState()
    }

    fun update(action: OnboardingAction): OnboardingState =
        db.transaction {
            val raw = saved()
            require(raw == null || !requiresExplicitRestart(raw) || action == OnboardingAction.Restart) {
                "Restart setup explicitly to replace an unsupported setup state"
            }
            val next = OnboardingReducer.reduce(load() ?: OnboardingState(step = OnboardingStep.READY, completed = true), action)
            db.execute("INSERT OR REPLACE INTO application_settings VALUES (?, ?)", listOf(KEY, encode(next)))
            next
        }

    private fun saved(): String? = db.query("SELECT value FROM application_settings WHERE key=?", listOf(KEY)).singleOrNull()?.single()

    private fun hasPortfolioData(): Boolean =
        listOf("owners", "accounts", "portfolios", "targets", "ledger_activities").any {
            db.query("SELECT 1 FROM $it LIMIT 1").isNotEmpty()
        }

    private fun requiresExplicitRestart(raw: String): Boolean {
        // Size alone requires preservation: a future document can place its version anywhere.
        if (raw.length > MAX_LENGTH) return true
        return runCatching {
            Json
                .parseToJsonElement(raw)
                .jsonObject["version"]!!
                .jsonPrimitive.int > VERSION
        }.getOrDefault(false)
    }

    private fun decode(raw: String): OnboardingState? =
        runCatching {
            require(raw.length <= MAX_LENGTH)
            val fields = Json.parseToJsonElement(raw).jsonObject
            require(fields.getValue("version").jsonPrimitive.int == VERSION)
            val sources =
                fields
                    .getValue("sources")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            require(sources.all { id -> SupportedSources.choices.any { it.id == id } })
            val state =
                OnboardingState(
                    step = OnboardingStep.valueOf(fields.getValue("step").jsonPrimitive.content),
                    goals =
                        fields
                            .getValue("goals")
                            .jsonArray
                            .map { OnboardingGoal.valueOf(it.jsonPrimitive.content) }
                            .toSet(),
                    sourceIds = sources,
                    presentation = OnboardingPresentation.valueOf(fields.getValue("presentation").jsonPrimitive.content),
                    completed = fields.getValue("completed").jsonPrimitive.boolean,
                    skipped = fields.getValue("skipped").jsonPrimitive.boolean,
                )
            require(!state.completed || state.step == OnboardingStep.READY)
            state
        }.getOrNull()

    private fun encode(state: OnboardingState): String =
        buildJsonObject {
            put("version", VERSION)
            put("step", state.step.name)
            put("goals", JsonArray(state.goals.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) }))
            put("sources", JsonArray(state.sourceIds.sorted().map { JsonPrimitive(it) }))
            put("presentation", state.presentation.name)
            put("completed", state.completed)
            put("skipped", state.skipped)
        }.toString()

    companion object {
        const val KEY = "onboarding_state"
        private const val VERSION = 1
        private const val MAX_LENGTH = 8192
    }
}
