-- Die Abmeldung ist keine Journey mehr (docs/ideen/logout-ueber-keycloak.md): Den Intent LOGOUT und
-- seinen Zustand gibt es nicht mehr, gespeicherte Journeys damit liessen sich nicht mehr lesen. Eine
-- solche Journey hatte nie ein Tool; die erste Zeile ist nur die Absicherung des Fremdschluessels.
-- Der Journey-Trace behaelt seine Zeilen, nur ohne Intent.
DELETE FROM orchestrator.tool_session
WHERE journey_id IN (SELECT id FROM orchestrator.auth_journey WHERE intent = 'LOGOUT');
DELETE FROM orchestrator.auth_journey WHERE intent = 'LOGOUT';
UPDATE orchestrator.journey_trace SET intent = NULL WHERE intent = 'LOGOUT';

-- Der Back-Channel Logout nennt nur die Keycloak-Sitzung. Ueber sie findet der Orchestrator die
-- Kanaele und App-Anmeldungen dieser Sitzung, ohne die Tabellen ganz zu lesen.
CREATE INDEX ix_channel_session_kc_durable_session_id ON orchestrator.channel_session (kc_durable_session_id);
CREATE INDEX ix_app_token_session_keycloak_session_id ON orchestrator.app_token_session (keycloak_session_id);
