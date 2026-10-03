package com.example.identity.tools.ident_kvnr.internal

/** The working data of one ident-kvnr run, kept through `ToolSessionData`: what was typed, kept across a reload. */
internal data class IdentKvnrToolSession(
    val kvnr: String? = null,
    val partnerNumber: String? = null,
)
