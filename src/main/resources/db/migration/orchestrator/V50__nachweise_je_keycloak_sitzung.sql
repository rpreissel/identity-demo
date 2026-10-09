-- Was eine Keycloak-Sitzung nachgewiesen hat, eine Zeile je Sitzung und Verfahren (ADR-59). Ein
-- neuer Web-Kanal derselben Sitzung übernimmt die Zeilen; parallele Tabs schreiben verschiedene
-- Zeilen, keiner überschreibt den anderen. Ersetzt das signierte RestoreData-Token.
CREATE TABLE orchestrator.keycloak_session_evidence (
    kc_session_id      VARCHAR(64)              NOT NULL,
    method             VARCHAR(64)              NOT NULL,
    account_id         BIGINT                   NOT NULL,
    loa                VARCHAR(16)              NOT NULL,
    enrolled_under_acr VARCHAR(16),
    -- FactorType-Namen, durch Komma getrennt.
    factor_types       VARCHAR(128)             NOT NULL,
    amr_source_id      VARCHAR(64)              NOT NULL,
    axis               VARCHAR(32)              NOT NULL,
    proven_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Das späteste Ende der Keycloak-Sitzung ohne weitere Aktivität (ADR-43).
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (kc_session_id, method)
);
CREATE INDEX ix_keycloak_session_evidence_account ON orchestrator.keycloak_session_evidence (account_id, method);
CREATE INDEX ix_keycloak_session_evidence_expires ON orchestrator.keycloak_session_evidence (expires_at);
