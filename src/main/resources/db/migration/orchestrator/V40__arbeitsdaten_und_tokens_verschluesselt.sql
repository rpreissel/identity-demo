-- Arbeitsdaten der Tools und die Tokens der App-Sitzungen liegen verschluesselt (ADR-53).

-- Ein Datenschluessel je Aufbewahrungsklasse und Tag, eingepackt mit dem KEK (ADR-52). Nach
-- retire_after braucht ihn keine Zeile mehr; RetentionJob loescht ihn, und damit alles, was noch
-- unter ihm liegt, etwa in Sicherungen.
CREATE TABLE orchestrator.data_key (
    key_id          VARCHAR(64)   PRIMARY KEY,
    retention_class VARCHAR(32)   NOT NULL,
    wrapped_key     VARBINARY(64) NOT NULL,
    kek_version     VARCHAR(32)   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    retire_after    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_data_key_retire_after ON orchestrator.data_key (retire_after);

-- Die Arbeitsdaten (ADR-49) bleiben JSON, aber als Chiffrat unter dem Datenschluessel data_key_id.
-- Offene Durchlaeufe verlieren ihre Daten samt Typ und beginnen beim naechsten Schritt von vorn.
ALTER TABLE orchestrator.tool_session DROP COLUMN data;
UPDATE orchestrator.tool_session SET data_type = NULL;
ALTER TABLE orchestrator.tool_session ADD COLUMN data VARBINARY(1000000);
ALTER TABLE orchestrator.tool_session ADD COLUMN data_key_id VARCHAR(64);

-- Die Tokens sind ein Zwischenspeicher; eingepackt unter dem Hauptschluessel des Kontos. Vorhandene
-- Zeilen verlieren ihren Zwischenspeicher und holen beim naechsten Aufruf ein neues Token.
ALTER TABLE orchestrator.app_token_session DROP COLUMN access_token;
ALTER TABLE orchestrator.app_token_session DROP COLUMN refresh_token;
ALTER TABLE orchestrator.app_token_session ADD COLUMN access_token VARBINARY(8192);
ALTER TABLE orchestrator.app_token_session ADD COLUMN refresh_token VARBINARY(8192);
