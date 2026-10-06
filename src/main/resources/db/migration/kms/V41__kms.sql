-- Schema des Moduls `kms` - der simulierte Schluesseldienst, ein Fremdsystem wie `kobil` (ADR-54).
-- Das Schluesselmaterial liegt hier, weil die Simulation der Tresor ist; ein echter Dienst (Vault
-- Transit, KMS, HSM) gaebe es nie heraus. Unsere Tabellen halten nur, was dieser Dienst eingepackt
-- oder signiert hat, nie die Schluessel selbst.

CREATE SCHEMA IF NOT EXISTS kms;

-- Ein benannter Schluessel mit Typ, wie ein Transit-Key: AES256 zum Einpacken, ECDSA_P256 zum Signieren.
CREATE TABLE kms.transit_key (
    name                   VARCHAR(64)  PRIMARY KEY,
    key_type               VARCHAR(16)  NOT NULL,
    latest_version         INT          NOT NULL,
    -- Aeltere Versionen packen nicht mehr aus und verifizieren nicht mehr: so zieht man einen alten
    -- Schluessel aus dem Verkehr, ohne ihn sofort zu vernichten.
    min_decryption_version INT          NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Jede Rotation ist eine neue Version. Das Material bleibt, bis die Version unter
-- min_decryption_version faellt und ein Demo-Aufruf sie vernichtet.
CREATE TABLE kms.transit_key_version (
    key_name   VARCHAR(64)  NOT NULL,
    version    INT          NOT NULL,
    material   VARBINARY(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_kms_key_version PRIMARY KEY (key_name, version),
    CONSTRAINT fk_kms_key_version_key FOREIGN KEY (key_name) REFERENCES kms.transit_key (name) ON DELETE CASCADE
);
