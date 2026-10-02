package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.orchestrator.SharedSpringContext
import com.nimbusds.jose.jwk.ECKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * A key pair is created on first use, and the first use may come from a listener that runs after
 * the commit (`KeycloakSessionLogoutListener`). There the caller's transaction is over, so the
 * insert has to bring its own.
 */
class NodeKeysDbTest(
    private val repository: NodeSigningKeyRepository,
    private val transactionManager: PlatformTransactionManager,
) : SharedSpringContext({

    given("a purpose without a key pair yet") {
        `when`("the first use happens after the commit of the surrounding transaction") {
            val purpose = "test:" + UUID.randomUUID()
            val keys = NodeKeys(repository, Clock.systemUTC())
            var created: Result<String>? = null

            TransactionTemplate(transactionManager).execute {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        created = runCatching { keys.keyFor(purpose, "test").keyID }
                    }
                })
            }

            then("the pair is created and stored") {
                val keyId = created.shouldNotBeNull().getOrThrow()
                ECKey.parse(repository.findById(purpose).get().publicKeyJwk).keyID shouldBe keyId
            }
        }
    }
})
