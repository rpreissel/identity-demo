package com.example.identity.core.orchestrator.admin

import com.example.identity.demo.demo_mode.DemoSurface
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.contract.tool_api.directory.PersonDirectory
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AdminAccountView(
    val accountId: Long,
    val personId: String?,
    val displayName: String?,
    val email: String?,
    /** Active method names, one per instance (several devices show up several times). */
    val methods: List<String>
)

/**
 * Accounts from the operator's side: list, delete one, or reset the demo ([DemoReset]). Deletion
 * goes through [AccountDeletionService], the same path as the DELETE_ACCOUNT journey, so no method
 * module's credentials stay behind. Exists only in demo mode.
 */
@RestController
@DemoSurface
@RequestMapping(ADMIN_API)
@Tag(name = "Admin: accounts", description = "List and delete accounts, reset the demo")
class AdminAccountsController(
    private val accountService: AccountService,
    private val accountDeletionService: AccountDeletionService,
    private val personDirectory: PersonDirectory,
    private val demoReset: DemoReset,
) {

    @GetMapping("accounts")
    @Operation(summary = "All accounts")
    fun accounts(): List<AdminAccountView> =
        accountService.allAccountIds().sorted().mapNotNull { accountService.findAccount(it) }.map { profile ->
            AdminAccountView(
                accountId = profile.accountId,
                personId = profile.personId,
                displayName = profile.personId?.let { personDirectory.displayName(it) },
                email = profile.email,
                methods = profile.activeAuthenticationMethods.map { it.method }
            )
        }

    @DeleteMapping("accounts/{accountId}")
    @Operation(summary = "Delete one account", description = "Same cleanup as the account's own deletion journey; its channels end up logged out.")
    fun delete(@PathVariable accountId: Long): ResponseEntity<Void> {
        if (accountService.findAccount(accountId) == null) return ResponseEntity.notFound().build()
        accountDeletionService.deleteAccount(accountId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("demo-reset")
    @Operation(summary = "Put the demo back to its start", description = DemoReset.DESCRIPTION)
    fun reset(): DemoResetResult = demoReset.reset()
}
