package com.example.identity.core.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Persistent device-to-account pairing, independent of any ChannelSession (ADR-3). A DPoP key only
 * proves which device is talking; resuming a session needs a known `channelSessionId`. This link
 * lets a known device go straight to login on a new channel, without a fresh `ident-fsc`.
 */
@Entity
@Table(schema = "orchestrator", name = "device_account_link")
class DeviceAccountLink(
    @Id
    @Column(name = "binding_key_ref", nullable = false, length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }
}
