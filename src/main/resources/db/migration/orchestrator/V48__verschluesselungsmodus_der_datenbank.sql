-- Der Modus, mit dem diese Datenbank angelegt wurde (ADR-55): verschluesselt oder lesbare Demo.
-- EncryptionModeGuard prueft ihn vor jeder Migration gegen identity.encryption.enabled und bricht
-- ab, ohne etwas zu aendern, wenn er abweicht. Eine Zeile, nie geaendert.
CREATE TABLE orchestrator.encryption_mode (
    encrypted BOOLEAN NOT NULL
);
INSERT INTO orchestrator.encryption_mode (encrypted) VALUES (${encryption_enabled});
