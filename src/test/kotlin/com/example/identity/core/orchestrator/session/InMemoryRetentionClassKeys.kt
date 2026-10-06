package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_CLOCK
import com.example.identity.core.account.DataKeyWrapping
import com.example.identity.core.account.application.ConfiguredKekWrapper
import com.example.identity.core.account.application.PreviousMasterKeks
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

/** The real key hierarchy over a test KEK and an in-memory key table; transactions are no-ops. */
class InMemoryRetentionClassKeys {
    val stored = mutableMapOf<String, DataKey>()
    val repository = mockk<DataKeyRepository> {
        every { findById(any()) } answers { Optional.ofNullable(stored[firstArg()]) }
        every { saveAndFlush(any<DataKey>()) } answers { firstArg<DataKey>().also { stored[it.keyId!!] = it } }
    }
    val wrapping = DataKeyWrapping(ConfiguredKekWrapper("test-kek-of-at-least-32-characters", "1", PreviousMasterKeks()))
    val keys = RetentionClassKeys(repository, wrapping, ToolSessionRetentionProperties(), NoTransactions, TEST_CLOCK)

    private object NoTransactions : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }
}
