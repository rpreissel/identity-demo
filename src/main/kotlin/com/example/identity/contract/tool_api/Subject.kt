package com.example.identity.contract.tool_api

/**
 * Whom a lookup tool proved: the tool resolved it from the submitted input itself. An account in
 * the ordinary case; an invitation for a one-time password (`auth-invite`,
 * docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md), whose session then has no account at all.
 */
sealed interface Subject {
    data class Account(val id: Long) : Subject

    /** [id] is the invitation's identity, the digest of its one-time password. */
    data class Invitation(val id: String) : Subject
}
