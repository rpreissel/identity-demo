package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.ids.MasterKeyId
import java.util.HexFormat
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * How a sealed value lies in its column (ADR-55). With encryption on, the column holds the bare
 * ciphertext (AES-256-GCM, the key's reference bound in as additional data) and nothing else. In
 * the demo (`identity.encryption.enabled=false`) values lie readable, led by a short header naming
 * the key they would be sealed under, each part named so nobody has to remember the layout:
 * `[konto 3f9a2b1c pruefwert a17e03c9]` for a master key, `[gruppe 7c1d0e2a pruefwert ...]` for a
 * claim batch, `[tag TOOL_SESSION:2026-10-07 pruefwert ...]` for a day's data key, `[ohne]` for a
 * row of no account. `pruefwert` is a short HMAC under the key over header, additional data and
 * value: a readable value still opens only under its key, and a wrong key fails the way it fails
 * in the real mode. A key is wrapped in every mode; in the demo its header says so:
 * `[verschluesselt mit konto 3f9a2b1c]`.
 * The mode is fixed per database (`EncryptionModeGuard`): reading follows the mode.
 */
@Component
class Envelopes(@Value("\${identity.encryption.enabled:true}") val encryptionEnabled: Boolean = true) {

    /** [encrypt] defaults to the mode; a key wrap passes `true`, since keys stay wrapped in every mode. */
    fun seal(key: SecretKey, keyRef: String, aad: ByteArray, plaintext: ByteArray, encrypt: Boolean = encryptionEnabled): ByteArray {
        if (encryptionEnabled) return AesGcm.seal(key, aad + keyRef.toByteArray(), plaintext)
        if (encrypt) {
            val header = "[$ENCRYPTED $keyRef]".toByteArray()
            return header + AesGcm.seal(key, aad + header, plaintext)
        }
        return header(keyRef, "$TAG ${tag(key, keyRef, aad, plaintext)}").toByteArray() + plaintext
    }

    fun open(key: SecretKey, keyRef: String, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (encryptionEnabled) return AesGcm.open(key, aad + keyRef.toByteArray(), sealed)
        val (header, payloadStart) = headerOf(sealed)
        val fields = header.removePrefix("[").removeSuffix("]")
        val encrypted = fields.startsWith("$ENCRYPTED ")
        val sealedUnder = if (encrypted) fields.removePrefix("$ENCRYPTED ") else fields.substringBefore(" $TAG ")
        check(sealedUnder == keyRef) { "value sealed under '$sealedUnder', asked with '$keyRef'" }
        val payload = sealed.copyOfRange(payloadStart, sealed.size)
        if (encrypted) return AesGcm.open(key, aad + header.toByteArray(), payload)
        // The same failure as a wrong key in the real mode.
        if (fields.substringAfter(" $TAG ", "") != tag(key, keyRef, aad, payload)) throw AEADBadTagException("value under '$keyRef' does not open with this key")
        return payload
    }

    /** A row that belongs to no account and so has no key: readable in every mode, headed `[ohne]` in the demo. */
    fun plain(payload: ByteArray): ByteArray = if (encryptionEnabled) payload else "[$NO_KEY]".toByteArray() + payload

    fun openPlain(sealed: ByteArray): ByteArray {
        if (encryptionEnabled) return sealed
        val (header, payloadStart) = headerOf(sealed)
        check(header == "[$NO_KEY]") { "not a plain row: $header" }
        return sealed.copyOfRange(payloadStart, sealed.size)
    }

    /** The demo header of a row, for a reader that only wants to know - `null` outside the demo. */
    fun describe(sealed: ByteArray): String? = if (encryptionEnabled) null else runCatching { headerOf(sealed).first }.getOrNull()

    /** The demo form of a digest: the key's header, then the readable normalized value. */
    fun readableDigest(keyRef: String, value: String): String = "[$keyRef]$value"

    private fun header(keyRef: String, suffix: String) = "[$keyRef $suffix]"

    /** Eight hex characters of an HMAC under [key]: short enough to read past, long enough that a wrong key never matches by chance in a demo. */
    private fun tag(key: SecretKey, keyRef: String, aad: ByteArray, payload: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encoded, "HmacSHA256")) }
        mac.update(keyRef.toByteArray()); mac.update(aad)
        return HexFormat.of().formatHex(mac.doFinal(payload)).take(8)
    }

    /** The header and where the payload starts: `[` up to the first `]`. */
    private fun headerOf(sealed: ByteArray): Pair<String, Int> {
        require(sealed.isNotEmpty() && sealed[0] == '['.code.toByte()) { "not a demo row: no header" }
        val end = sealed.indexOf(']'.code.toByte())
        require(end > 0) { "not a demo row: header incomplete" }
        return String(sealed, 0, end + 1, Charsets.US_ASCII) to end + 1
    }

    companion object {
        const val ENCRYPTED = "verschluesselt mit"
        const val TAG = "pruefwert"
        const val NO_KEY = "ohne"

        /** The eight leading characters of an id: enough to find the key in the console (`CAST(key_id AS VARCHAR) LIKE '3f9a2b1c%'`). */
        private fun short(id: UUID) = id.toString().take(8)

        fun masterKey(id: MasterKeyId) = "konto ${short(id.value)}"
        fun batch(id: UUID) = "gruppe ${short(id)}"
        fun dataKey(keyId: String) = "tag $keyId"

        /** What a demo header adds to a column at most (`[tag TOOL_SESSION:2026-10-07 pruefwert a3f91c2e]`); the schema widens by it. */
        const val HEADER_ALLOWANCE = 64
    }
}
