package com.example.identity.core.orchestrator.dpop

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.text.ParseException
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class DpopValidator(
    private val jwkThumbprintService: JwkThumbprintService,
    private val replayProtectionService: DpopReplayProtectionService,
    @Value("\${dpop.proof.max-clock-skew-seconds:30}") private val maxClockSkewSeconds: Long,
    @Value("\${dpop.proof.max-age-seconds:60}") private val maxProofAgeSeconds: Long
) {

    fun validate(dpopProof: String?, httpMethod: String, httpUrl: String): DpopProof {
        if (dpopProof.isNullOrBlank()) {
            throw DpopValidationException(DpopFailure.MISSING)
        }

        val signedJWT: SignedJWT = try {
            SignedJWT.parse(dpopProof)
        } catch (e: ParseException) {
            throw DpopValidationException(DpopFailure.MALFORMED, cause = e)
        }

        val header = signedJWT.header
        validateHeader(header)

        val jwk = header.jwk
        if (jwk == null || jwk.isPrivate) {
            throw DpopValidationException(DpopFailure.INVALID_KEY)
        }

        val claims: JWTClaimsSet = try {
            signedJWT.jwtClaimsSet
        } catch (e: ParseException) {
            throw DpopValidationException(DpopFailure.INVALID_CLAIMS, cause = e)
        }

        validateSignature(signedJWT, jwk, header.algorithm)
        validateClaims(claims, httpMethod, httpUrl)

        val thumbprint = jwkThumbprintService.computeThumbprint(jwk)
        val issuedAt = claims.issueTime.toInstant()
        val replayKeyExpiresAt = issuedAt.plus(maxProofAgeSeconds + maxClockSkewSeconds, ChronoUnit.SECONDS)
        replayProtectionService.validateAndStore(thumbprint, claims.getJWTID(), replayKeyExpiresAt)

        return try {
            DpopProof(
                dpopProof,
                jwk,
                claims.getJWTID(),
                claims.getStringClaim("htm"),
                claims.getStringClaim("htu"),
                issuedAt,
                claims.getStringClaim("nonce")
            )
        } catch (e: ParseException) {
            throw DpopValidationException(DpopFailure.INVALID_CLAIMS, cause = e)
        }
    }

    private fun validateHeader(header: JWSHeader) {
        if (header.type == null || header.type.type == null
            || !DPOP_JWT_TYPE.equals(header.type.type, ignoreCase = true)) {
            throw DpopValidationException(DpopFailure.WRONG_TYPE)
        }
        if (header.algorithm !in SUPPORTED_ALGORITHMS) {
            throw DpopValidationException(DpopFailure.UNSUPPORTED_ALGORITHM, "alg ${header.algorithm}")
        }
    }

    private fun validateSignature(signedJWT: SignedJWT, jwk: JWK, algorithm: JWSAlgorithm) {
        try {
            val valid: Boolean = when (jwk) {
                is ECKey -> signedJWT.verify(ECDSAVerifier(jwk.toECPublicKey()))
                else -> throw DpopValidationException(DpopFailure.INVALID_KEY, "kty ${jwk.keyType}")
            }
            if (!valid) {
                throw DpopValidationException(DpopFailure.INVALID_SIGNATURE)
            }
        } catch (e: JOSEException) {
            throw DpopValidationException(DpopFailure.INVALID_SIGNATURE, cause = e)
        }
    }

    private fun validateClaims(claims: JWTClaimsSet, httpMethod: String, httpUrl: String) {
        try {
            val htm = claims.getStringClaim("htm")
            if (htm == null || !htm.equals(httpMethod, ignoreCase = true)) {
                throw DpopValidationException(DpopFailure.HTM_MISMATCH)
            }

            val htu = claims.getStringClaim("htu")
            if (htu == null) {
                throw DpopValidationException(DpopFailure.HTU_MISMATCH, "htu missing")
            }
            if (!htuMatches(htu, httpUrl)) {
                throw DpopValidationException(DpopFailure.HTU_MISMATCH)
            }

            val issuedAt = claims.issueTime?.toInstant()
                ?: throw DpopValidationException(DpopFailure.IAT_MISSING)
            val now = Instant.now()
            if (issuedAt.isAfter(now.plus(maxClockSkewSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException(DpopFailure.IAT_IN_FUTURE)
            }
            if (issuedAt.isBefore(now.minus(maxProofAgeSeconds, ChronoUnit.SECONDS))) {
                throw DpopValidationException(DpopFailure.IAT_TOO_OLD)
            }

            val jti = claims.getJWTID()
            if (jti.isNullOrBlank()) {
                throw DpopValidationException(DpopFailure.JTI_MISSING)
            }
        } catch (e: ParseException) {
            throw DpopValidationException(DpopFailure.INVALID_CLAIMS, cause = e)
        }
    }


    companion object {
        private const val DPOP_JWT_TYPE = "dpop+jwt"
        private val SUPPORTED_ALGORITHMS: Set<JWSAlgorithm> = setOf(
            JWSAlgorithm.ES256,
            JWSAlgorithm.ES384,
            JWSAlgorithm.ES512
        )
    }
}
