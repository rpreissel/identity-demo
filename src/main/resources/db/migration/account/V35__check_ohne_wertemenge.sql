-- CHECK ohne Wertemenge (KONVENTIONEN.md): H2 macht aus "x IN ('a', 'b')" eine Suche, die ueber die
-- Sitzung vergleicht, die die Constraint uebersetzt hat - die Pool-Verbindung der Migration. Ist sie
-- ersetzt (Hikari max-lifetime), scheitert jede Pruefung mit "The database has been closed".
ALTER TABLE account.change_log DROP CONSTRAINT ck_change_log_type;
ALTER TABLE account.change_log ADD CONSTRAINT ck_change_log_type CHECK (CASE change_type
    WHEN 'IDENTIFIED' THEN TRUE WHEN 'ATTRIBUTE_RETRACTED' THEN TRUE WHEN 'METHOD_ADDED' THEN TRUE
    WHEN 'METHOD_DEACTIVATED' THEN TRUE WHEN 'ACCOUNT_DELETED' THEN TRUE WHEN 'ACCOUNT_ABSORBED' THEN TRUE
    ELSE FALSE END);

ALTER TABLE account.sign_in_log DROP CONSTRAINT ck_sign_in_log_type;
ALTER TABLE account.sign_in_log ADD CONSTRAINT ck_sign_in_log_type CHECK (CASE sign_in_type
    WHEN 'SIGNED_IN' THEN TRUE WHEN 'STEPPED_UP' THEN TRUE WHEN 'SIGN_IN_FAILED' THEN TRUE
    WHEN 'LOCKED_OUT' THEN TRUE WHEN 'SIGNED_OUT' THEN TRUE
    ELSE FALSE END);
