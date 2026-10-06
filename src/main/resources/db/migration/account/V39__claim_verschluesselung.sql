-- Umschlagverschluesselung des Claim-Logs (ADR-52): Werte liegen verschluesselt, Gleichheit laeuft
-- ueber einen HMAC je Konto. Drei Stufen: Umschlagschluessel (KEK, Konfiguration oder KMS) ->
-- Hauptschluessel je Konto (account.account) -> Datenschluessel je Gruppe (account.claim_batch_key).
-- Keine Datenuebernahme: es gibt keine Produktivdaten, die Demo-Datenbank wird neu aufgebaut.

-- Hauptschluessel des Kontos, eingepackt mit dem KEK der Version kek_version.
ALTER TABLE account.account ADD COLUMN wrapped_master_key VARBINARY(64) NOT NULL;
ALTER TABLE account.account ADD COLUMN kek_version VARCHAR(32) NOT NULL;

-- Ein Datenschluessel je Gruppe (ein Aufruf von recordClaims, eine Aufbewahrungsregel). Den Schluessel
-- zu loeschen macht die Werte der Gruppe dauerhaft unlesbar; die Zeilen in account.claim bleiben.
-- expires_at steht schon beim Schreiben fest, damit der Aufraeumlauf nur diesen Index liest.
CREATE TABLE account.claim_batch_key (
    claim_batch_id UUID          PRIMARY KEY,
    account_id     BIGINT        NOT NULL,
    wrapped_dek    VARBINARY(64) NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_claim_batch_key_account FOREIGN KEY (account_id) REFERENCES account.account (id) ON DELETE CASCADE
);
CREATE INDEX ix_claim_batch_key_expires_at ON account.claim_batch_key (expires_at);

-- claim_value: Nonce, Chiffrat und Pruefwert (AES-256-GCM) unter dem Datenschluessel der Gruppe.
-- value_digest: HMAC-SHA256 des normalisierten Werts unter dem Hauptschluessel des Kontos - nur
-- fuer Gleichheit innerhalb eines Kontos (Dedup, Widerruf), nie zum Suchen ueber Konten.
ALTER TABLE account.claim DROP COLUMN claim_value;
ALTER TABLE account.claim DROP COLUMN normalized_value;
ALTER TABLE account.claim ADD COLUMN claim_value VARBINARY(1100) NOT NULL;
ALTER TABLE account.claim ADD COLUMN value_digest VARCHAR(64) NOT NULL;
ALTER TABLE account.claim ADD COLUMN claim_batch_id UUID NOT NULL;
CREATE INDEX ix_claim_batch_id ON account.claim (claim_batch_id);

-- Der Widerruf trifft denselben Digest; ein Klartext steht hier nicht mehr. Ein Widerruf nach
-- abgelaufener Frist nennt seine Gruppe und trifft nur deren Zeilen; sonst ist claim_batch_id NULL
-- und der Widerruf gilt fuer jede aeltere Zeile mit diesem Digest.
DROP INDEX account.ix_retraction_account_type_value;
ALTER TABLE account.retraction DROP COLUMN normalized_value;
ALTER TABLE account.retraction ADD COLUMN value_digest VARCHAR(64) NOT NULL;
ALTER TABLE account.retraction ADD COLUMN claim_batch_id UUID;
CREATE INDEX ix_retraction_account_type_digest ON account.retraction (account_id, attribute_type, value_digest);
