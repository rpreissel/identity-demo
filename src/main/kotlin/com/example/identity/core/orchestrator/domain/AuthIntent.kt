package com.example.identity.core.orchestrator.domain

/**
 * What the user wants to achieve, together with the strategy that leads them there
 * (docs/04-orchestrierung.md #2). The two are inseparable: "get me in" and "offer the device
 * first, then other methods, and identification only as a last resort" are one decision, not two.
 *
 * Not a description of what a run turned out to be. Whether a run was a registration or a login is
 * an observation about the path taken, not a goal chosen up front. So there is no
 * REGISTRATION/LOGIN pair here.
 *
 * One row per intent, every property named: a new intent has to decide each of them, as a missing
 * one would silently inherit a default (a wrong [linksDeviceWithoutAsking] is destructive).
 *
 * @property entry How a client opens a new channel with this intent; `null` for an intent reached
 *   only from within a session.
 * @property inSession How a signed-in user starts it within the session (docs/04-orchestrierung.md
 *   Abschnitt 2), never as a sub-journey; `null` if not at all. [CONFIRM_PEER_LOGIN] has both.
 * @property raisesLevel Raises the level of a login, direct or as another journey's precondition.
 *   While it runs on a signed-in channel, the channel shows STEP_UP_IN_PROGRESS; when it finishes,
 *   the sign-in log records a step-up (ADR-39).
 * @property linksDeviceWithoutAsking Whether succeeding on an APP channel links this device to the
 *   account as a side effect (`DeviceAccountLink`, docs/09-dpop.md #3), or whether the intent asks
 *   first. Only [LOOKUP_LOGIN] asks. A property of the intent, not a flag on each Action: it never
 *   varies within an intent. A wrong `true` is destructive, because a link that moves to another
 *   account revokes that account's device credentials for this key. Answers only whether the intent
 *   wants to bind; whether there is a device at all is checked once in
 *   `JourneyActionExecutor.linkDeviceTo`.
 */
enum class AuthIntent(
    val entry: Entry?,
    val inSession: InSession?,
    val raisesLevel: Boolean,
    val linksDeviceWithoutAsking: Boolean,
) {
    /** Into a login on this device as fast as possible, in a way that works again next time. */
    FAST_ACCESS(
        entry = Entry(APP, withDeviceAccount = true), inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /** Deliberately fresh identification, even on an already linked device. */
    REGISTER(
        entry = Entry(APP_OR_WEB, withDeviceAccount = false), inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /**
     * Log into an existing account without a paired device (classic web login). The one intent that
     * does not link the device as a side effect ([linksDeviceWithoutAsking]). It asks first
     * (`DeviceLinkQuestion`): people choose it because they do not want to be recognized
     * next time.
     */
    LOOKUP_LOGIN(
        entry = Entry(APP, withDeviceAccount = false), inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = false,
    ),

    /**
     * Entry intent for the Keycloak facade (docs/04-orchestrierung.md Abschnitt 3): offers every Keycloak-usable
     * tool as one `selectMethod` step, without fallback chain or enrollment; Keycloak only renders
     * the tools' pages (ADR-58). Serves initial login (resolves an account like [LOOKUP_LOGIN]) and step-up (account
     * pre-set on the channel).
     */
    WEB_SELECT_METHOD(
        entry = Entry(WEB, withDeviceAccount = false), inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /** Raise the level. Only on an AUTHENTICATED channel. */
    STEP_UP(
        entry = null, inSession = InSession(needsAccount = false),
        raisesLevel = true, linksDeviceWithoutAsking = true,
    ),

    /** Add or remove authentication methods. Only on an AUTHENTICATED channel. */
    MANAGE_AUTH_METHODS(
        entry = null, inSession = InSession(needsAccount = true),
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /**
     * Approve or decline a web login that an `auth-qr`/`auth-qr-lookup` pairing is waiting on. The
     * only intent that is both an entry intent (a cold app scanning the QR) and reachable on an
     * authenticated channel; both paths reach the same gate. The loa2 gate is [STEP_UP] with the
     * device's known account (`DeviceAccountLink`). Without a known account the run aborts; it never
     * falls back to identification or registration.
     */
    CONFIRM_PEER_LOGIN(
        entry = Entry(APP, withDeviceAccount = true), inSession = InSession(needsAccount = true),
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /** Delete the account itself, after a fresh re-confirmation. Only on an AUTHENTICATED channel. */
    DELETE_ACCOUNT(
        entry = null, inSession = InSession(needsAccount = true),
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /**
     * "This action needs the level and a recent proof": the sub-journey every self-service action
     * runs first (docs/journeys/fresh-proof.md, ADR-61). Never an entry intent, never started
     * directly; reached only as another intent's [Transition.RequireSubJourney].
     */
    FRESH_PROOF(
        entry = null, inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = true,
    ),

    /**
     * "No active method reaches the target - re-identify instead?" Never an entry intent. Reached as
     * another intent's [Transition.RequireSubJourney] once no active method can close the ACR gap.
     * Which account a fresh identification may lead to is decided by the handler of
     * `Action.RecordIdentification`, not here (see [ReIdentifyStrategy]).
     */
    RE_IDENTIFY(
        entry = null, inSession = null,
        raisesLevel = false, linksDeviceWithoutAsking = true,
    );

    /**
     * Opens a new channel on [on] (docs/04-orchestrierung.md Abschnitt 2). FAST_ACCESS, LOOKUP_LOGIN
     * and CONFIRM_PEER_LOGIN assume an App channel with device binding; WEB_SELECT_METHOD is
     * Keycloak's own entry. [withDeviceAccount]: an APP channel starts from the account this device
     * is linked to (`DeviceAccountLink`), when opened and again after a cancel. One rule for both,
     * or a cancelled cold CONFIRM_PEER_LOGIN would lose its account. REGISTER and LOOKUP_LOGIN mean
     * "not the account this device already knows".
     */
    data class Entry(val on: Set<ChannelType>, val withDeviceAccount: Boolean)

    /**
     * Started by a signed-in user, only on an AUTHENTICATED channel; see [startRefusal].
     * [needsAccount]: acts on the account itself. A process access (one-time password, ADR-48) is
     * signed in without an account and serves only its process, so it may raise its level but not
     * manage, delete or confirm.
     */
    data class InSession(val needsAccount: Boolean)

    /**
     * Why this intent may not start on an open channel in [state], or null if it may. The one place
     * that decides "only on an AUTHENTICATED channel" for every intent a signed-in user starts.
     */
    fun startRefusal(state: ChannelState?, hasAccount: Boolean): StartRefusal? = when {
        inSession == null -> StartRefusal.NOT_STARTABLE_IN_SESSION
        state != ChannelState.AUTHENTICATED -> StartRefusal.NOT_LOGGED_IN
        inSession.needsAccount && !hasAccount -> StartRefusal.NO_ACCOUNT
        else -> null
    }

    enum class StartRefusal { NOT_STARTABLE_IN_SESSION, NOT_LOGGED_IN, NO_ACCOUNT }

    companion object {
        /**
         * The entry intent a client names when opening a [channel]. `null` means the channel's
         * default: FAST_ACCESS for the App, WEB_SELECT_METHOD for Keycloak. Otherwise matches the
         * intent's name case-insensitively (e.g. "register"), so there is no separate wire
         * vocabulary. Returns null for an unknown name or one this channel type cannot be opened
         * with; the caller rejects it.
         */
        fun fromRequest(value: String?, channel: ChannelType): AuthIntent? {
            if (value == null) {
                return when (channel) {
                    ChannelType.APP -> FAST_ACCESS
                    ChannelType.WEB -> WEB_SELECT_METHOD
                }
            }
            return entries.firstOrNull { it.entry != null && channel in it.entry.on && it.name.equals(value, ignoreCase = true) }
        }
    }
}

// Shorthands for the entry channels in the table above.
private val APP = setOf(ChannelType.APP)
private val WEB = setOf(ChannelType.WEB)
private val APP_OR_WEB = setOf(ChannelType.APP, ChannelType.WEB)
