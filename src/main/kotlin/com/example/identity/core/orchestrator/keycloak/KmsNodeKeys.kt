package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.tool_api.kms.KeyService
import com.example.identity.contract.tool_api.kms.KmsKeyType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.impl.ECDSA
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.util.Base64URL
import org.springframework.stereotype.Component

/**
 * The signing keys of this node, one per purpose, kept in the KMS (ADR-54): the private key never
 * leaves it, the orchestrator sends the signing input and gets the signature back. Every instance
 * signs with the same keys, and a restart changes nothing. The JWKS publishes every version the KMS
 * still verifies, so Keycloak finds a rotated key by its `kid` without a restart on either side.
 */
@Component
class KmsNodeKeys(private val kms: KeyService) {

    /** `kid` of version [version] of [purpose], as published in its JWKS. */
    fun keyId(purpose: String, version: Int): String = "$purpose-v$version"

    /**
     * Makes sure the key of [purpose] exists, in the KMS's own transaction. Called at start for
     * every purpose a signer knows, so a request never creates a key inside its own transaction.
     */
    fun provision(purpose: String) {
        kms.ensureKey(purpose, KmsKeyType.ECDSA_P256)
    }

    /** A Nimbus signer for ES256 over the current version of [purpose]; the header must name [KmsJwsSigner.keyId]. */
    fun signer(purpose: String): KmsJwsSigner = KmsJwsSigner(purpose)

    /** The public keys of every usable version of [purpose], newest first. */
    fun publicKeys(purpose: String): List<ECKey> {
        val info = infoOf(purpose)
        return info.usableVersions.sortedDescending().map { version ->
            ECKey.Builder(Curve.P_256, kms.publicKey(purpose, version)).keyID(keyId(purpose, version)).algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).build()
        }
    }

    /** Read only; the key is created by [provision], or on a first use before the start has finished. */
    private fun infoOf(purpose: String) = kms.findKey(purpose) ?: kms.ensureKey(purpose, KmsKeyType.ECDSA_P256)

    inner class KmsJwsSigner(private val purpose: String) : JWSSigner {
        // One row read; the key exists since provisioning, or is created once before the start has finished.
        private val version = runCatching { kms.latestVersion(purpose) }.getOrElse { infoOf(purpose).latestVersion }

        /** The kid the header must carry: the version that signs now. */
        val keyId: String = keyId(purpose, version)

        override fun sign(header: JWSHeader, signingInput: ByteArray): Base64URL {
            require(header.algorithm == JWSAlgorithm.ES256) { "only ES256 is signed by the KMS, not ${header.algorithm}" }
            val signed = kms.sign(purpose, signingInput)
            check(signed.keyVersion == version) { "KMS key '$purpose' rotated to v${signed.keyVersion} while signing with $keyId" }
            // The KMS answers DER; a JWS carries R || S.
            return Base64URL.encode(ECDSA.transcodeSignatureToConcat(signed.bytes, ECDSA.getSignatureByteArrayLength(JWSAlgorithm.ES256)))
        }

        override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = setOf(JWSAlgorithm.ES256)

        override fun getJCAContext(): JCAContext = JCAContext()
    }

    companion object {
        /** The purpose of the key that signs for client [clientId] at Keycloak's token endpoint. */
        fun keycloakClientAuth(clientId: String): String = "keycloak-client-auth:$clientId"
    }
}
