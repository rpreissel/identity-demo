package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.contract.tool_api.envelope.ActiveMethodView
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/*
 * The response shapes the channel services build. They live next to the services, not in `api.v1`:
 * there is one global API version (docs/05-api.md), so a shape is not a v1 thing. Routes, request
 * bodies and binding stay in `api.v1`.
 */

@Schema(description = "The account's active authentication methods (docs/05-api.md #2). Never contains fsc.")
data class MethodsResponse(
    val methods: List<ActiveMethodView>
)

@Schema(
    description = "Mock Keycloak AccessToken (a spec-shaped unsecured JWT, alg=none - parse and " +
        "display its payload, no verification needed) plus both token lifetimes. The RefreshToken " +
        "value itself is deliberately never part of this response - it's a credential and stays " +
        "server-side; refreshExpiresAt is the only thing about it exposed."
)
data class TokenResponse(
    @field:Schema(example = "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiIsImFjciI6ImxvYTIiLCJhbXIiOlsic21zIl19.")
    val accessToken: String,
    val tokenType: String = "Bearer",
    @field:Schema(example = "2026-08-28T18:05:00Z")
    val accessExpiresAt: Instant,
    @field:Schema(example = "2026-08-29T18:00:00Z")
    val refreshExpiresAt: Instant
)

/** One key-bound credential on the calling device, see [DeviceLinkResponse.boundCredentials]. */
data class BoundCredentialView(
    @field:Schema(example = "kobil") val method: String,
    @field:Schema(example = "dev-1a2b3c4d5e6f") val reference: String
)

@Schema(
    description = "Whether this device's DPoP key is already linked to an account (DeviceAccountLink, " +
        "docs/02-domaenenmodell.md #1) - a pure read, no channel/journey created. Lets the entry screen show " +
        "\"this device belongs to X\" before the user picks how to start."
)
data class DeviceLinkResponse(
    val linked: Boolean,
    @field:Schema(example = "42")
    val accountId: Long? = null,
    @field:Schema(
        description = "Demo-only: what else this device is known by - one entry per key-bound " +
            "credential of the linked account living on THIS key, with the reference its own " +
            "method discloses (docs/09-dpop.md). The `device` method names its credential key, " +
            "`kobil` the identifier the provider gave this phone. Absence is meaningful: a client " +
            "that holds local data for a method no longer listed here is holding something stale."
    )
    val boundCredentials: List<BoundCredentialView> = emptyList()
)

/**
 * One method a native Keycloak authenticator proved in this flow run (docs/05-api.md Abschnitt 3,
 * ADR-8). Only two ids: method, loa and factor types are fixed per authenticator type and come from
 * [NativeAuthenticatorDescriptor] via [nativeToolId]. [amrSourceId] names this proof instance and
 * stays the same on refreshes, so a refresh is not taken for a new proof.
 */
@Schema(description = "One native authenticator proof - which authenticator TYPE, and which specific execution/instance of it.")
data class AmrEntry(
    @field:Schema(example = "kc-otp-form") val nativeToolId: String,
    @field:Schema(example = "kc-otp-form-exec-1") val amrSourceId: String
)

/**
 * What a new kc channel may resume from without re-proving it (docs/05-api.md Abschnitt 3).
 * [evidence] is the real `AuthEvidence`, not a lossy copy. Fetched from its own endpoint by the
 * authenticator's end-of-flow hook only. On the wire it is always signed by [RestoreDataCodec] and
 * bound to its UserSession, so a leaked note cannot hand evidence to another session.
 */
data class RestoreData(
    val accountId: Long? = null,
    val evidence: AuthEvidence? = null
)
