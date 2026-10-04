package com.example.identity.core.orchestrator.domain

enum class ChannelState {
    ANONYMOUS,
    REGISTERING,
    AUTHENTICATED,
    STEP_UP_REQUIRED,
    STEP_UP_IN_PROGRESS,
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
     * The channel has a login: AUTHENTICATED, or a step-up running on top of one. A cancelled
     * journey returns here, one rule for every intent (docs/04-orchestrierung.md, "Abbruch").
     */
    val isLoggedIn: Boolean
        get() = this == AUTHENTICATED || this == STEP_UP_IN_PROGRESS

    /**
     * What clients and the admin view see (ADR-46). REGISTERING is never stored: an ANONYMOUS
     * channel shows it while it works with an account still being set up, so the state cannot
     * disagree with the account.
     */
    fun shownWith(accountBeingSetUp: Boolean): ChannelState =
        if (this == ANONYMOUS && accountBeingSetUp) REGISTERING else this
}
