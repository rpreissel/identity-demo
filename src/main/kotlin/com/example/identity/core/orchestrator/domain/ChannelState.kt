package com.example.identity.core.orchestrator.domain

enum class ChannelState {
    ANONYMOUS,
    REGISTERING,
    AUTHENTICATED,
    LOGGED_OUT,
    EXPIRED;

    /**
     * This channelSessionId is dead for good (docs/02-domaenenmodell.md #3, docs/05-api.md #1):
     * `next` is absent, and a new channel needs a fresh `POST /channels`. Resume and cancel do not
     * revive it.
     */
    val isTerminal: Boolean
        get() = this == LOGGED_OUT || this == EXPIRED

    /**
     * The channel has a login. Whether a journey still runs on it (a step-up, a method change) is
     * not a state of its own: `next` says what is due. A cancelled journey returns to this status,
     * one rule for every intent (docs/04-orchestrierung.md, "Abbruch").
     */
    val isLoggedIn: Boolean
        get() = this == AUTHENTICATED

    /**
     * What clients and the admin view see (ADR-46). REGISTERING is never stored: an ANONYMOUS
     * channel shows it while it works with an account still being set up, so the state cannot
     * disagree with the account.
     */
    fun shownWith(accountBeingSetUp: Boolean): ChannelState =
        if (this == ANONYMOUS && accountBeingSetUp) REGISTERING else this
}
