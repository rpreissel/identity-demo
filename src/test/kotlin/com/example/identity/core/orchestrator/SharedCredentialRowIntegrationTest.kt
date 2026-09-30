package com.example.identity.core.orchestrator

import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.contract.tool_api.EnrollmentRef
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.support.GeneratedKeyHolder

/**
 * A device key rebound to another account reuses its credential row (by thumbprint). Deleting - or
 * revoking on - the old account must leave that row alone while the new account's method still points
 * at it; otherwise the new account runs into a 500.
 */
class SharedCredentialRowIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var accountService: AccountService

    @Autowired
    private lateinit var accountDeletionService: AccountDeletionService

    private fun deviceRow(): Long {
        val keys = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                "INSERT INTO auth_device.enrollment (kty, crv, x, y, thumbprint, created_at) VALUES ('EC', 'P-256', 'x', 'y', 'thumb-shared', CURRENT_TIMESTAMP)",
                arrayOf("id")
            )
        }, keys)
        return keys.key!!.toLong()
    }

    private fun rowExists(id: Long) =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_device.enrollment WHERE id = ?", Int::class.java, id) == 1

    init {
        given("two accounts whose device methods point at the same credential row") {
            then("deleting the first keeps the row; deleting the last one removes it") {
                val row = deviceRow()
                val ref = EnrollmentRef("auth_device.enrollment", row.toString())
                val first = accountService.createAccountInSetup().accountId
                val second = accountService.createAccountInSetup().accountId
                accountService.addAuthenticationMethod(first, "device", ref, enrolledUnderAcr = null, details = emptyMap(), allowsMultipleInstances = true)
                accountService.addAuthenticationMethod(second, "device", ref, enrolledUnderAcr = null, details = emptyMap(), allowsMultipleInstances = true)

                accountDeletionService.deleteAccount(first)
                rowExists(row) shouldBe true

                accountDeletionService.deleteAccount(second)
                rowExists(row) shouldBe false
            }
        }
    }
}
