package com.example.identity.contract.tool_api.claims

/**
 * One of the known acr levels ("none", "loa1".."loa3"), used on both sides of the tool contract
 * ([Tool.maxAcr][com.example.identity.contract.tool_api.Tool.maxAcr], [ToolOutcome.Completed.achievedAcr]). Not an enum, because raw values
 * arrive from untyped borders (tokens, columns, wire DTOs): [parse] answers `null` for an unknown
 * one, while the constructor rejects in-process typos.
 */
@JvmInline
value class AcrLevel(val value: String) : Comparable<AcrLevel> {
    init {
        require(value in KNOWN) { "Unknown acr level: $value (known: $KNOWN)" }
    }

    override fun compareTo(other: AcrLevel): Int = rank(this).compareTo(rank(other))

    override fun toString(): String = value

    companion object {
        /** All known levels, lowest first; the ordering everything below derives from. */
        val KNOWN = listOf("none", "loa1", "loa2", "loa3")

        /** Nothing established: an anonymous channel, and what a border reads an unknown level as. */
        val NONE = AcrLevel("none")

        /** One factor proven, e.g. an SMS code or a password on its own. */
        val LOA1 = AcrLevel("loa1")

        /**
         * Two factors of distinct [FactorType]s, or one tool that reaches it alone (`auth-device`,
         * `ident-fsc`). Required for anything that changes the account itself.
         */
        val LOA2 = AcrLevel("loa2")

        /** The highest level; no tool in this demo reaches it, so it only ever appears as a target. */
        val LOA3 = AcrLevel("loa3")

        /** Input from outside: `null` for a missing or unknown level. */
        fun parse(raw: String?): AcrLevel? = raw?.takeIf { it in KNOWN }?.let(::AcrLevel)

        /** Position in [KNOWN]; `null` counts as "nothing established" (rank 0, like [NONE]). */
        fun rank(acr: AcrLevel?): Int = acr?.let { KNOWN.indexOf(it.value) }?.takeIf { it >= 0 } ?: 0

        /** Inverse of [rank] - the level at a given rank, [NONE] if out of range. */
        fun levelAt(rank: Int): AcrLevel = KNOWN.getOrNull(rank)?.let(::AcrLevel) ?: NONE

        /** The higher of two levels; a `null` argument falls back to the other, both `null` -> [NONE]. */
        fun max(a: AcrLevel?, b: AcrLevel?): AcrLevel {
            if (a == null) return b ?: NONE
            if (b == null) return a
            return if (a >= b) a else b
        }

        /** The lower of two levels; any `null` argument means nothing was established -> [NONE]. */
        fun min(a: AcrLevel?, b: AcrLevel?): AcrLevel {
            if (a == null || b == null) return NONE
            return if (a <= b) a else b
        }
    }
}
