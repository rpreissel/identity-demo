package com.example.identity.core.account.application

import javax.crypto.SecretKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Every sealed value says what it is sealed with (ADR-55): a readable header
 * `ide1;key=<key reference>;alg=aes-256-gcm;` or `...;alg=none;` leads the bytes, then the payload.
 * [open] checks the header against the key it was asked with, so a value cannot be presented under
 * another key, and the header is bound into the ciphertext. In demo mode encryption can be switched
 * off (`identity.encryption.enabled=false`): values are then stored readable, still with the header
 * naming the key they would be sealed under, and keys are still wrapped. Reading follows the row,
 * not the switch, so it may be flipped at any time. `ProductionModeCheck` refuses the switch off.
 */
@Component
class Envelopes(@Value("\${identity.encryption.enabled:true}") val encryptionEnabled: Boolean = true) {

    /** [encrypt] defaults to the switch; a key wrap passes `true`, since keys stay wrapped in every mode. */
    fun seal(key: SecretKey, keyRef: String, aad: ByteArray, plaintext: ByteArray, encrypt: Boolean = encryptionEnabled): ByteArray {
        val header = header(keyRef, if (encrypt) AES_GCM else NONE).toByteArray()
        return header + if (encrypt) AesGcm.seal(key, aad + header, plaintext) else plaintext
    }

    fun open(key: SecretKey, keyRef: String, aad: ByteArray, sealed: ByteArray): ByteArray {
        val (header, payloadStart) = headerOf(sealed)
        val fields = header.removePrefix("$MAGIC;").split(';').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val sealedUnder = fields["key"] ?: error("sealed value without a key reference")
        check(sealedUnder == keyRef) { "value sealed under '$sealedUnder', asked with '$keyRef'" }
        val payload = sealed.copyOfRange(payloadStart, sealed.size)
        return when (val alg = fields["alg"]) {
            AES_GCM -> AesGcm.open(key, aad + header.toByteArray(), payload)
            NONE -> payload
            else -> error("unknown algorithm '$alg' in a sealed value")
        }
    }

    /** A row that belongs to no account and so has no key: readable, headed `key=none;alg=none;`. */
    fun plain(payload: ByteArray): ByteArray = header(NO_KEY, NONE).toByteArray() + payload

    fun openPlain(sealed: ByteArray): ByteArray {
        val (header, payloadStart) = headerOf(sealed)
        check(header == header(NO_KEY, NONE)) { "not a plain row: $header" }
        return sealed.copyOfRange(payloadStart, sealed.size)
    }

    /** What a row is sealed with, for a reader that only wants to know - `null` if it carries no header. */
    fun describe(sealed: ByteArray): String? = runCatching { headerOf(sealed).first }.getOrNull()

    private fun header(keyRef: String, alg: String) = "$MAGIC;key=$keyRef;alg=$alg;"

    /** The header and where the payload starts: the magic, then two fields, each ended by `;`. */
    private fun headerOf(sealed: ByteArray): Pair<String, Int> {
        val prefix = "$MAGIC;".toByteArray()
        require(sealed.size > prefix.size && sealed.copyOfRange(0, prefix.size).contentEquals(prefix)) { "not a sealed value: no header" }
        var semicolons = 0
        var end = prefix.size
        while (end < sealed.size && semicolons < 2) {
            if (sealed[end] == ';'.code.toByte()) semicolons++
            end++
        }
        require(semicolons == 2) { "not a sealed value: header incomplete" }
        return String(sealed, 0, end, Charsets.US_ASCII) to end
    }

    companion object {
        const val MAGIC = "ide1"
        const val AES_GCM = "aes-256-gcm"
        const val NONE = "none"
        const val NO_KEY = "none"
    }
}
