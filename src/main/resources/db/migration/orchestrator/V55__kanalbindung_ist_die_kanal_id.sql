-- Ein Web-Kanal ist an seine eigene channel_session_id gebunden, die Keycloaks Assertion nennt.
-- channel_binding hielt immer genau diesen Wert und entfällt (ADR-64).
ALTER TABLE orchestrator.channel_session DROP COLUMN channel_binding;
