package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.MasterKeyId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * A master key, wrapped with the KEK (ADR-52), and whose it is. Created for a journey before its
 * account exists ([accountId] null, ADR-55) and adopted by the account the journey ends up in; an
 * account's first key is its [primary] one, under which its claims and tokens are sealed. A key
 * that no account ever adopted is deleted with the channels that could still name it.
 */
@Entity
@Table(schema = "account", name = "master_key")
class MasterKey(
    @Id
    @Column(name = "key_id", nullable = false)
    var keyUuid: UUID? = null,

    @Column(name = "account_id")
    var accountId: AccountId? = null,

    @Column(name = "primary_key", nullable = false)
    var primary: Boolean = false,

    @Column(name = "wrapped_master_key", nullable = false)
    var wrappedMasterKey: ByteArray? = null,

    @Column(name = "kek_version", nullable = false)
    var kekVersion: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
) : Persistable<UUID> {
    @Transient
    private var persisted = false

    /** The typed id; the column stays a bare UUID, as every other UUID id does. */
    val keyId: MasterKeyId? get() = keyUuid?.let(::MasterKeyId)

    override fun getId(): UUID? = keyUuid

    override fun isNew(): Boolean = !persisted

    @jakarta.persistence.PostPersist
    @jakarta.persistence.PostLoad
    fun markPersisted() {
        persisted = true
    }
}

@Repository
interface MasterKeyRepository : JpaRepository<MasterKey, UUID> {
    fun findKey(keyId: MasterKeyId): MasterKey? = findById(keyId.value).orElse(null)

    fun findByAccountIdAndPrimaryTrue(accountId: AccountId?): MasterKey?

    fun findByAccountId(accountId: AccountId?): List<MasterKey>

    /** Every KEK version some master key is wrapped with - which KEKs are still needed. */
    @Query("select distinct k.kekVersion from MasterKey k")
    fun kekVersions(): Set<String>

    /** Keys no account adopted, older than [cutoff]: the journey that got them is long gone. */
    @Modifying
    @Query("delete from MasterKey k where k.accountId is null and k.createdAt < :cutoff")
    fun deletePendingCreatedBefore(@Param("cutoff") cutoff: Instant): Int
}
