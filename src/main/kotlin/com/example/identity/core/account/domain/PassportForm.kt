package com.example.identity.core.account.domain

import java.text.Normalizer

/**
 * Uppercase, umlauts spelled out, other diacritics dropped: how a passport chip writes a name. The
 * attestation check and the change log's search key must use the same rule, or a person found by
 * one is missed by the other.
 */
internal fun passportForm(value: String): String =
    Normalizer.normalize(
        value.trim().uppercase()
            .replace("Ä", "AE").replace("Ö", "OE").replace("Ü", "UE").replace("ß", "SS").replace("ẞ", "SS"),
        Normalizer.Form.NFD
    ).replace("\\p{M}".toRegex(), "").replace("[^A-Z0-9-]".toRegex(), "")
