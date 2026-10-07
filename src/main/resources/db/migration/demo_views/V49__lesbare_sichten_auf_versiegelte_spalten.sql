-- Nur in der Demo (Verzeichnis demo_views, ModuleMigrationLocations). Die versiegelten Spalten
-- sind binaer, die H2-Konsole zeigt sie als Hex. Je Tabelle eine Sicht mit Endung _demo_readable, die
-- diese Spalten als Text liefert: in der Demo der Kopf und der lesbare Wert (ADR-55), bei
-- eingeschalteter Verschluesselung unlesbare Zeichen. Eingepackte Schluessel bleiben binaer.
-- Eine Sicht haengt an ihren Spalten: Wer eine davon aendert oder entfernt, legt vorher die Sicht
-- neu an oder laesst sie fallen.
CREATE VIEW account.claim_demo_readable AS
SELECT id, account_id, attribute_type, claim_source, established_acr, auth_method_id, established_at,
       UTF8TOSTRING(claim_value) AS claim_value, value_digest, claim_batch_id
FROM account.claim;

CREATE VIEW account.auth_method_demo_readable AS
SELECT id, account_id, method, enrollment_type, enrollment_id, active, enrolled_under_acr,
       UTF8TOSTRING(label) AS label, bound_key_ref, UTF8TOSTRING(reference) AS reference,
       allows_multiple_instances, created_at, deactivated_at
FROM account.auth_method;

CREATE VIEW account.sign_in_log_demo_readable AS
SELECT id, account_id, invitation, sign_in_type, channel, acr, UTF8TOSTRING(details) AS details, occurred_at
FROM account.sign_in_log;

CREATE VIEW auth_sms.enrollment_demo_readable AS
SELECT id, UTF8TOSTRING(phone_number) AS phone_number, key_id, created_at
FROM auth_sms.enrollment;

CREATE VIEW auth_kobil.enrollment_demo_readable AS
SELECT id, kobil_tenant_id, kobil_user_id, kobil_device_id, label, UTF8TOSTRING(pin) AS pin, key_id,
       unlock_secret_hash, binding_key_ref, created_at
FROM auth_kobil.enrollment;

CREATE VIEW orchestrator.tool_session_demo_readable AS
SELECT id, journey_id, status, data_type, UTF8TOSTRING(data) AS data, data_key_id, created_at, expires_at
FROM orchestrator.tool_session;

CREATE VIEW orchestrator.app_token_session_demo_readable AS
SELECT id, account_id, session_evidence_id, keycloak_session_id, auth_time,
       UTF8TOSTRING(access_token) AS access_token, UTF8TOSTRING(refresh_token) AS refresh_token,
       access_expires_at, refresh_expires_at, updated_at
FROM orchestrator.app_token_session;
