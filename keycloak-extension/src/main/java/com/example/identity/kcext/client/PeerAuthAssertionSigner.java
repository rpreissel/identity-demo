package com.example.identity.kcext.client;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Date;
import java.util.UUID;

/**
 * Builds and signs the JWT for each kc-facade request (docs/12-entscheidungen.md ADR-7,
 * docs/02-domaenenmodell.md Abschnitt 1). The {@code channel_binding} claim is this flow run's
 * {@code channelSessionId}, so two tabs stepping up the same SSO session never share a binding.
 * {@code htu} binds the URL with its query, {@code body_sha256} the body, so nobody on the hop can
 * swap either under a valid assertion; the binding also covers endpoints without a channelSessionId
 * in the path ({@code /tools/api/{toolId}/v{N}/{toolSessionId}/...}).
 */
final class PeerAuthAssertionSigner {

    private final String issuer;
    private final String audience;
    private final ECKey signingKey;

    PeerAuthAssertionSigner(String issuer, String audience, ECKey signingKey) {
        this.issuer = issuer;
        this.audience = audience;
        this.signingKey = signingKey;
    }

    String sign(String httpMethod, String httpUrl, String channelSessionId, byte[] body) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(new Date(nowSeconds * 1000))
                .claim("htm", httpMethod)
                .claim("htu", httpUrl)
                .claim("channel_binding", channelSessionId)
                .claim("body_sha256", sha256(body));

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("peer-auth+jwt")).keyID(signingKey.getKeyID()).build(),
                claims.build()
        );
        try {
            jwt.sign(new ECDSASigner(signingKey));
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to sign peer-auth assertion", e);
        }
        return jwt.serialize();
    }

    /** The jti of an assertion this signer produced - what the orchestrator's answer must name ({@code req}). */
    static String jtiOf(String assertion) {
        try {
            return SignedJWT.parse(assertion).getJWTClaimsSet().getJWTID();
        } catch (java.text.ParseException e) {
            throw new IllegalStateException("Own peer-auth assertion unreadable", e);
        }
    }

    /** Base64url SHA-256, the same form as the response signature's {@code body_sha256}. */
    static String sha256(byte[] body) {
        try {
            return Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
