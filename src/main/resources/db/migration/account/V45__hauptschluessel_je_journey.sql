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

-- Die Breite der versiegelten Spalten haengt vom Modus ab (Platzhalter aus
-- ClaimEncryptionKeys.schemaPlaceholders, gesetzt von EncryptionModeGuard,
-- ADR-55): mit Verschluesselung das nackte Chiffrat, in der Demo der lesbare Wert hinter einem
-- kurzen Kopf, der den Schluessel nennt. Ein Digest ist dann der normalisierte Wert selbst.
ALTER TABLE account.claim_batch_key ALTER COLUMN wrapped_dek SET DATA TYPE VARBINARY(${wrapped_key_width});
ALTER TABLE account.claim ALTER COLUMN claim_value SET DATA TYPE VARBINARY(${claim_value_width});
ALTER TABLE account.claim ALTER COLUMN value_digest SET DATA TYPE VARCHAR(${digest_width});
ALTER TABLE account.retraction ALTER COLUMN value_digest SET DATA TYPE VARCHAR(${digest_width});
