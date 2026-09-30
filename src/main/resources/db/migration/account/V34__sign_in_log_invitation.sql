-- Das Anmeldeprotokoll nennt auch Vorgangszugaenge (ADR-48): eine Zeile gehoert einem Konto oder einer
-- Einladung, nie beiden. Eine Einladungszeile hat kein Konto, mit dem sie gehen koennte; sie lebt nur
-- account.sign-in-log.retention-months.
ALTER TABLE account.sign_in_log ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE account.sign_in_log ADD COLUMN invitation VARCHAR(64);
ALTER TABLE account.sign_in_log ADD CONSTRAINT ck_sign_in_log_one_subject
    CHECK ((account_id IS NULL) <> (invitation IS NULL));
CREATE INDEX ix_sign_in_log_invitation ON account.sign_in_log (invitation, occurred_at);
