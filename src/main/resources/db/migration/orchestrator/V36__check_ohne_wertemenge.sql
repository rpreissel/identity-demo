-- CHECK ohne Wertemenge (KONVENTIONEN.md): H2 macht aus "x IN ('a', 'b')" eine Suche, die ueber die
-- Sitzung vergleicht, die die Constraint uebersetzt hat - die Pool-Verbindung der Migration. Ist sie
-- ersetzt (Hikari max-lifetime), scheitert jede Pruefung mit "The database has been closed".
ALTER TABLE orchestrator.tool_session DROP CONSTRAINT ck_tool_session_status;
ALTER TABLE orchestrator.tool_session ADD CONSTRAINT ck_tool_session_status CHECK (CASE status
    WHEN 'RUNNING' THEN TRUE WHEN 'DONE' THEN TRUE WHEN 'ABANDONED' THEN TRUE ELSE FALSE END);

-- I-1: ein beendeter Kanal (LOGGED_OUT, EXPIRED) haelt weder Tokens noch Evidenz.
ALTER TABLE orchestrator.channel_session DROP CONSTRAINT ck_channel_session_ended_without_login;
ALTER TABLE orchestrator.channel_session ADD CONSTRAINT ck_channel_session_ended_without_login
    CHECK (CASE state WHEN 'LOGGED_OUT' THEN FALSE WHEN 'EXPIRED' THEN FALSE ELSE TRUE END
        OR (app_token_session_id IS NULL AND session_evidence_id IS NULL));
