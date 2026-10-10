package com.example.identity.kcext.event;

import com.example.identity.kcext.model.OrchestratorComponent;

import com.example.identity.kcext.client.OrchestratorClient;
import com.example.identity.kcext.client.OrchestratorSettings;
import com.example.identity.kcext.federation.InvitationStorageProviderFactory;
import com.example.identity.kcext.model.KcSubject;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.StorageId;

import java.util.Optional;

/**
 * Reports every Keycloak logout of an orchestrator account or invitation for the sign-in log (ADR-39, ADR-48):
 * the Web channel's logout is Keycloak's own, so the orchestrator would not learn of it otherwise.
 * Sent after Keycloak's transaction committed and fire-and-forget: a lost report costs one log line,
 * a failed logout would cost the user.
 */
public class SignInLogEventListener implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(SignInLogEventListener.class);

    private final KeycloakSession session;

    SignInLogEventListener(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.LOGOUT) {
            return;
        }
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        var component = realm == null ? null : OrchestratorComponent.in(realm).orElse(null);
        if (component == null) {
            return;
        }
        String invitationComponentId = InvitationStorageProviderFactory.componentIn(realm).map(ComponentModel::getId).orElse(null);
        Optional<KcSubject> reportable = subjectToReport(
                event.getType(), event.getUserId(), event.getSessionId(), component.getId(), invitationComponentId);
        if (reportable.isEmpty()) {
            return;
        }
        KcSubject subject = reportable.get();
        OrchestratorClient client = OrchestratorSettings.from(component).newClient();
        String kcSessionId = event.getSessionId();
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                try {
                    client.reportSignOut(subject, kcSessionId);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    LOG.warnf("Could not report the logout of %s to the orchestrator: %s", subject, e.getMessage());
                }
            }

            @Override
            protected void rollbackImpl() {
                // The logout did not happen - nothing to report.
            }
        });
    }

    /**
     * The subject to report, if any: only a {@code LOGOUT} with a session, of a user from one of the
     * orchestrator's two federations - an account (numeric external id) or an invitation (ADR-48).
     */
    static Optional<KcSubject> subjectToReport(
            EventType type, String userId, String sessionId, String accountComponentId, String invitationComponentId) {
        if (type != EventType.LOGOUT || userId == null || sessionId == null) {
            return Optional.empty();
        }
        String provider = StorageId.providerId(userId);
        String externalId = StorageId.externalId(userId);
        if (accountComponentId.equals(provider)) {
            try {
                return Optional.of(KcSubject.account(Long.parseLong(externalId)));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        if (invitationComponentId != null && invitationComponentId.equals(provider) && externalId != null && !externalId.isBlank()) {
            return Optional.of(KcSubject.invitation(externalId));
        }
        return Optional.empty();
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Admin actions are no sign-outs of the holder.
    }

    @Override
    public void close() {
    }

    /** Registered as {@value #ID}; the realm lists it among its event listeners (keycloak-migrations V3). */
    public static class Factory implements EventListenerProviderFactory {
        public static final String ID = "orchestrator-sign-in-log";

        @Override
        public EventListenerProvider create(KeycloakSession session) {
            return new SignInLogEventListener(session);
        }

        @Override
        public void init(Config.Scope config) {
        }

        @Override
        public void postInit(KeycloakSessionFactory factory) {
        }

        @Override
        public void close() {
        }

        @Override
        public String getId() {
            return ID;
        }
    }
}
