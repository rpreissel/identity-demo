package com.example.identity.core.orchestrator.domain

/**
 * What the user wants to achieve, together with the strategy that leads them there
 * (docs/04-orchestrierung.md #1). The two are inseparable: "get me in" and "offer the device
 * first, then other methods, and identification only as a last resort" are one decision, not two.
 *
 * Not a description of what a run turned out to be. Whether a run was a registration or a login is
 * an observation about the path taken, not a goal chosen up front. So there is no
 * REGISTRATION/LOGIN pair here.
 */
enum class AuthIntent {
    /** Into a login on this device as fast as possible, in a way that works again next time. */
    FAST_ACCESS,

    /** Deliberately fresh identification, even on an already linked device. */
    REGISTER,

    /**
     * Log into an existing account without a paired device (classic web login). The one intent that
     * does not link the device as a side effect ([bindsDeviceImplicitly]). It asks first
     * (`LookupLoginState.OfferBinding`): people choose it because they do not want to be recognized
     * next time.
     */
    LOOKUP_LOGIN,

    /**
     * Entry intent for the kc-facade (docs/04-orchestrierung.md Abschnitt 3): offers every kc-usable
     * tool as one `selectMethod` step, without fallback chain or enrollment. Keycloak drives the rest
     * natively. Serves initial login (resolves an account like [LOOKUP_LOGIN]) and step-up (account
     * pre-set on the channel).
     */
    WEB_SELECT_METHOD,

    /** Raise the level. Only on an AUTHENTICATED channel. */
    STEP_UP,

    /** Add or remove authentication methods. Only on an AUTHENTICATED channel. */
    MANAGE_AUTH_METHODS,

    /**
     * Approve or decline a web login that an `auth-qr`/`auth-qr-lookup` pairing is waiting on. The
     * only intent that is both an entry intent (a cold app scanning the QR) and reachable on an
     * authenticated channel; both paths reach the same gate. The loa2 gate is [STEP_UP] with the
     * device's known account (`DeviceAccountLink`). Without a known account the run aborts; it never
     * falls back to identification or registration.
     */
    CONFIRM_PEER_LOGIN,

    /** Delete the account itself, after a fresh re-confirmation. Only on an AUTHENTICATED channel. */
    DELETE_ACCOUNT,

    /** Log out with a confirmation prompt. Only on an AUTHENTICATED channel. */
    LOGOUT,

    /**
     * "No active method reaches the target - re-identify instead?" Never an entry intent. Reached as
     * another intent's [Transition.RequireSubJourney] once no active method can close the ACR gap.
     * Which account a fresh identification may lead to is decided by the handler of
     * `Action.RecordIdentification`, not here (see [ReIdentifyStrategy]).
     */
    RE_IDENTIFY;

    /**
     * The intents a client may name when entering a channel. The others are reached from an
     * authenticated channel only; [CONFIRM_PEER_LOGIN] is both.
     */
    val isEntryIntent: Boolean
        get() = this == FAST_ACCESS || this == REGISTER || this == LOOKUP_LOGIN || this == WEB_SELECT_METHOD || this == CONFIRM_PEER_LOGIN

    /**
     * An APP channel entered with this intent starts from the account this device is linked to
     * (`DeviceAccountLink`), when opened and again after a cancel. One rule for both, or a cancelled
     * cold CONFIRM_PEER_LOGIN would lose its account. REGISTER and LOOKUP_LOGIN mean "not the account
     * this device already knows".
     */
    val startsFromDeviceLink: Boolean
        get() = this == FAST_ACCESS || this == CONFIRM_PEER_LOGIN

    /**
     * Whether succeeding on an APP channel links this device to the account as a side effect
     * (`DeviceAccountLink`, docs/09-dpop.md #3), or whether the intent asks first. Only
     * [LOOKUP_LOGIN] asks.
     *
     * A property of the intent, not a flag on each Action: it never varies within an intent. A
     * wrong `true` is destructive, because a link that moves to another account revokes that
     * account's device credentials for this key.
     *
     * Answers only whether the intent wants to bind. Whether there is a device at all depends on
     * the channel and is checked once in `JourneyActionExecutor.linkDeviceTo`.
     */
    val bindsDeviceImplicitly: Boolean
        get() = this != LOOKUP_LOGIN

    companion object {
        /**
         * `null` means FAST_ACCESS. Otherwise matches an entry intent's name case-insensitively
         * (e.g. "register"), so there is no separate wire vocabulary. Returns null for anything
         * else; the caller rejects it.
         */
        fun fromRequest(value: String?): AuthIntent? {
            if (value == null) return FAST_ACCESS
            return entries.firstOrNull { it.isEntryIntent && it.name.equals(value, ignoreCase = true) }
        }
    }
}
