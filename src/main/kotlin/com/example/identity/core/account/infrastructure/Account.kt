package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * Identity key and optimistic-lock root of an account, nothing else (ADR-14). [version] serializes
 * changes to the current state: `AccountService` bumps it before touching anchors or methods, so two
 * concurrent writers cannot both succeed (`409 CONCURRENT_MODIFICATION`). Log appends never bump it.
 * The account's master key (ADR-52) lives here wrapped, so it is backed up and deleted with the row.
 */
@Entity
@Table(schema = "account", name = "account")
class Account(
    var createdAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null

    /** The master key, wrapped with the KEK of [kekVersion]; `ClaimCrypto` is the only reader. */
    @Column(name = "wrapped_master_key", nullable = false)
    var wrappedMasterKey: ByteArray? = null

    @Column(name = "kek_version", nullable = false)
    var kekVersion: String? = null
}

/**
 * The two key columns alone. Read this way, the account row does not enter the persistence context,
 * so a later `findForUpdate` in the same transaction still raises the version.
 */
data class StoredMasterKey(val wrappedMasterKey: ByteArray, val kekVersion: String)

/** The typed id. The key itself stays a `Long`: JPA applies no converter to a generated `@Id`. */
val Account.accountId: AccountId get() = AccountId(checkNotNull(id) { "Account not saved yet" })
