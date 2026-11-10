package io.valroot.core.application

import io.valroot.core.storage.JvmSqliteDatabase
import io.valroot.testing.verifyBitcoinAccountingWorkflow
import kotlin.test.Test

class BitcoinAccountingWorkflowTest {
    @Test
    fun sourceHistoryVerificationAccountingAndRestorePreserveChainOrder() {
        verifyBitcoinAccountingWorkflow { JvmSqliteDatabase(":memory:") }
    }
}
