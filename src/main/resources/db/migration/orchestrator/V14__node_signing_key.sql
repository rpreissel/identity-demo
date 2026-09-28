-- Das Schluesselpaar, mit dem sich dieser Orchestrator bei Keycloak als Client ausweist
-- (private_key_jwt statt client_secret, docs/12-entscheidungen.md ADR-25).
--
-- Es liegt in der DB, damit mehrere Orchestrator-Instanzen dieselbe Client-Identitaet tragen.
-- purpose ist der Primaerschluessel: Zwei gleichzeitig startende Instanzen koennen so nicht zwei
-- Paare anlegen, die zweite liest das vorhandene. private_key_jwk liegt im Klartext (Demo-Rahmen,
-- ADR-22) und wird nur zum Signieren gelesen.
CREATE TABLE orchestrator.node_signing_key (
    purpose         VARCHAR(64)   PRIMARY KEY,
    public_key_jwk  VARCHAR(2000) NOT NULL,
    private_key_jwk VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
