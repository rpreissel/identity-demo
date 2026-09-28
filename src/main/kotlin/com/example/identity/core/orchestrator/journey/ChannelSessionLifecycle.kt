package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.kc.KeycloakSessionEnded
import com.example.identity.core.orchestrator.session.AppLoginSession
import com.example.identity.core.orchestrator.session.AuthContextService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionExpiredException
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.SessionRefusedException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * A channel's Keycloak session (ADR-43): opened when the channel logs in, renewed while it is used,
 * ended with the channel. What a lost session means for the journeys stays with [JourneyService];
 * this class only reports it ([SessionGone]).
 */
@Component
class ChannelSessionLifecycle(
    private val appLoginSession: AppLoginSession,
    private val authContextService: AuthContextService,
    private val sessionManagementService: SessionManagementService,
    private val journeyRecorder: JourneyRecorder,
    // Logout publishes KeycloakSessionEnded instead of calling Keycloak. Outside the `keycloak`
    // profile no listener exists, so this class needs no optional admin client.
    private val eventPublisher: ApplicationEventPublisher,
) {
    /** The channel's session has ended; [cause] says how. */
    class SessionGone(val cause: RuntimeException)

    /**
     * AUTHENTICATED and the Keycloak session come together: an APP channel fetches its token here,
     * which opens the login's one session or carries a step-up's acr into it. A refusal rolls the
     * whole transition back. A KEYCLOAK channel's session is opened by Keycloak itself.
     */
    fun open(channel: ChannelSession): SessionGone? {
        if (channel.channel != ChannelType.APP) return null
        return try {
            appLoginSession.tokenFor(channel)
            null
        } catch (e: SessionRefusedException) {
            throw OrchestratorException.invalidState(Text("Die Anmeldung konnte nicht abgeschlossen werden. Bitte versuchen Sie es noch einmal."), e.message)
        } catch (e: SessionExpiredException) {
            SessionGone(e)
        }
    }

    /**
     * Every journey interaction on a logged-in APP channel may renew its session, which keeps
     * Keycloak's idle window open while the user is active. A session Keycloak no longer renews is
     * gone, as on a token request.
     */
    fun keepAlive(channel: ChannelSession): SessionGone? {
        if (channel.channel != ChannelType.APP || channel.state?.isLoggedIn != true) return null
        return try {
            appLoginSession.keepAlive(channel)
            null
        } catch (e: SessionExpiredException) {
            SessionGone(e)
        } catch (e: SessionRefusedException) {
            SessionGone(e)
        }
    }

    /**
     * The one way a channel's login ends: logout, hard logout and expiry alike, so none of them
     * leaves the RefreshToken or the Keycloak session behind. An App channel ends exactly its own
     * Keycloak session; the Web channel's logout stays Keycloak's (docs/07-betrieb.md Abschnitt 3).
     */
    fun end(channel: ChannelSession, finalState: ChannelState) {
        check(finalState.isTerminal) { "endSession needs a terminal state, got $finalState" }
        // Only a sign-out someone asked for is one - an expiry is not an event (ADR-39, addendum).
        if (finalState == ChannelState.LOGGED_OUT) journeyRecorder.recordSignOut(channel, endedBy = "HOLDER")
        channel.authContextId?.let { authContextService.getAuthContext(it) }?.let { context ->
            if (channel.channel == ChannelType.APP) {
                // Published, not called: the Admin API call would hold the transaction open across
                // a network round trip. KeycloakSessionLogoutListener runs after commit.
                context.keycloakSessionId?.let { eventPublisher.publishEvent(KeycloakSessionEnded(it)) }
            }
            context.refreshToken = null
            context.accessToken = null
            context.refreshExpiresAt = null
            context.accessExpiresAt = null
            authContextService.save(context)
        }
        channel.authContextId = null
        channel.authEvidenceId = null
        channel.state = finalState
        sessionManagementService.updateChannelSession(channel)
    }
}
