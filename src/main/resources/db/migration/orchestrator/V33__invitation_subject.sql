-- Ein Kanal kann statt eines Kontos eine Einladung als Subjekt haben: die Anmeldung mit einem
-- Einmalkennwort (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Nie beides zugleich, und die
-- Evidenz gehoert genau einem von beiden.
ALTER TABLE orchestrator.channel_session ADD COLUMN invitation VARCHAR(64);
ALTER TABLE orchestrator.channel_session ADD CONSTRAINT ck_channel_session_one_subject
    CHECK (account_id IS NULL OR invitation IS NULL);

ALTER TABLE orchestrator.auth_evidence ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE orchestrator.auth_evidence ADD COLUMN invitation VARCHAR(64);
ALTER TABLE orchestrator.auth_evidence ADD CONSTRAINT ck_auth_evidence_one_subject
    CHECK ((account_id IS NULL AND invitation IS NOT NULL) OR (account_id IS NOT NULL AND invitation IS NULL));
