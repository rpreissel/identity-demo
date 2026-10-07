package com.example.identity.contract.tool_api.kms

import com.example.identity.contract.tool_api.ids.MasterKeyId

/** One master key, opened once; [purpose] is bound into every ciphertext. */
interface AccountSealer {
    fun seal(purpose: String, plaintext: ByteArray): ByteArray

    fun open(purpose: String, sealed: ByteArray): ByteArray
}

/**
 * Encryption under a master key of the account module (ADR-52, ADR-55) for a method module's
 * long-lived secrets: a phone number, a kept PIN. The tool context names the key
 * (`masterKeyId`): the account's own key, or the key the journey got before any account existed
 * and that the account adopts. A row stores the key id next to the ciphertext and reads it back by
 * that id, so no account needs to be known at read time. With the account go its keys, and with
 * them everything sealed here.
 */
interface AccountSealing {
    fun forKey(keyId: MasterKeyId): AccountSealer
}
