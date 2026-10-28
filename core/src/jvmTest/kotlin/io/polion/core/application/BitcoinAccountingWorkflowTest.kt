package io.polion.core.application

import io.polion.core.storage.JvmSqliteDatabase
import io.polion.testing.verifyBitcoinAccountingWorkflow
import kotlin.test.Test

class BitcoinAccountingWorkflowTest {
    @Test
    fun sourceHistoryVerificationAccountingAndRestorePreserveChainOrder() {
        verifyBitcoinAccountingWorkflow { JvmSqliteDatabase(":memory:") }
    }
}
