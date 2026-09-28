package com.example.identity.core.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * App-channel token bookkeeping in its own table (docs/02-domaenenmodell.md #1, ADR-15). Binds the
 * tokens [TokenService] issues to a channel. It holds no evidence; [authEvidenceId] points to the
 * [EvidenceTrail] the claims are resolved from. [keycloakSessionId] is the one session this login
 * holds, set with its first token and never replaced (ADR-43); the mock provider sets its own. A
 * logout ends exactly this session.
 */
@Entity
@Table(schema = "orchestrator", name = "auth_context")
class AuthContext(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "keycloak_session_id", length = 64)
    var keycloakSessionId: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var authContextId: UUID? = null

    /** The evidence this token context was minted from. [TokenService] resolves claims through it. */
    @Column(name = "auth_evidence_id")
    var authEvidenceId: UUID? = null

    /**
     * The AccessToken: a mock JWT by default, the real Keycloak `access_token` under `keycloak`,
     * hence the length. A cache, not a source of truth: repeated `.../token` calls return the same
     * token, and [AuthEvidenceService] clears it on every evidence change.
     */
    @Column(name = "access_token", length = 4096)
    var accessToken: String? = null

    /**
     * Never exposed to the frontend (docs/05-api.md): a credential. Under `keycloak` it is a
     * full-size refresh_token JWT, hence the length.
     */
    @Column(name = "refresh_token", length = 4096)
    var refreshToken: String? = null

    @Column(name = "auth_time", nullable = false)
    var authTime: Instant? = null

    @Column(name = "access_expires_at")
    var accessExpiresAt: Instant? = null

    @Column(name = "refresh_expires_at")
    var refreshExpiresAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        val now = Instant.now()
        authTime = now
        updatedAt = now
    }
}
