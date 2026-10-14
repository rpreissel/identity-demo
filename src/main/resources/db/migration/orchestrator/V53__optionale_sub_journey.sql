-- Ob eine Sub-Journey fuer ihren Aufrufer optional ist (Transition.RequireSubJourney.optional): Wird
-- sie abgebrochen, bricht der Aufrufer sonst mit ab, ohne dass seine Strategie gefragt wird.
ALTER TABLE orchestrator.auth_journey ADD COLUMN optional_for_parent BOOLEAN NOT NULL DEFAULT FALSE;

-- Bisher einzige optionale Sub-Journey: die abschliessende Identifizierung von "Enrollment zuerst".
-- Laufende Journeys dieser Art behalten ihr Verhalten.
UPDATE orchestrator.auth_journey SET optional_for_parent = TRUE
WHERE intent = 'RE_IDENTIFY'
  AND parent_journey_id IN (
      SELECT id FROM orchestrator.auth_journey WHERE intent = 'REGISTER' AND state_type = 'EnrollFirstStart'
  );
