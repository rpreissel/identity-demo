-- Hauptschluessel als eigene Tabelle (ADR-55): Ein Schluessel entsteht fuer eine Journey, bevor ihr
-- Konto existiert (account_id NULL), und wird vom Konto uebernommen. Der erste Schluessel eines
-- Kontos ist sein primaerer, unter ihm liegen Claims und Tokens; weitere uebernommene Schluessel
-- tragen die Zeilen der Verfahrensmodule, die sie nennen. Mit dem Konto gehen alle seine Schluessel.
CREATE TABLE account.master_key (
    key_id             UUID          PRIMARY KEY,
    account_id         BIGINT,
    primary_key        BOOLEAN       NOT NULL,
    wrapped_master_key VARBINARY(64) NOT NULL,
    kek_version        VARCHAR(32)   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_master_key_account FOREIGN KEY (account_id) REFERENCES account.account (id) ON DELETE CASCADE
);
CREATE INDEX ix_master_key_account_id ON account.master_key (account_id);
-- Fuer den Aufraeumlauf ueber Schluessel ohne Konto.
CREATE INDEX ix_master_key_created_at ON account.master_key (created_at);

ALTER TABLE account.account DROP COLUMN wrapped_master_key;
ALTER TABLE account.account DROP COLUMN kek_version;

-- Jeder versiegelte Wert traegt einen lesbaren Kopf ("ide1;key=...;alg=...;", ADR-55); die Spalten
-- brauchen dafuer Platz.
ALTER TABLE account.claim_batch_key ALTER COLUMN wrapped_dek SET DATA TYPE VARBINARY(256);
ALTER TABLE account.claim ALTER COLUMN claim_value SET DATA TYPE VARBINARY(1200);
-- Auch ein Digest nennt seinen Schluessel ("ide1;key=master:...;alg=hmac-sha256;" + Hex).
ALTER TABLE account.claim ALTER COLUMN value_digest SET DATA TYPE VARCHAR(160);
ALTER TABLE account.retraction ALTER COLUMN value_digest SET DATA TYPE VARCHAR(160);
