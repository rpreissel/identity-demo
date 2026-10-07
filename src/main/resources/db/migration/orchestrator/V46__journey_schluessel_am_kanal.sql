-- Der Hauptschluessel, unter dem eine Journey ohne Konto versiegelt (ADR-55). Das Konto, das die
-- Journey bindet, uebernimmt ihn (account.master_key).
ALTER TABLE orchestrator.channel_session ADD COLUMN journey_key_id UUID;
