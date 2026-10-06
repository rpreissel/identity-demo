-- Der Signaturschluessel des Orchestrators liegt im KMS (ADR-54), nicht mehr als privater JWK in
-- unserer Datenbank (ADR-22). Keycloak findet den neuen Schluessel ueber seine kid im JWKS; die
-- alten Paare braucht niemand mehr.
DROP TABLE orchestrator.node_signing_key;
