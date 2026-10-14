-- Eine Journey merkt sich die Schalter, die bei ihrem Start aktiv waren (JourneyFeatureFlag-Keys,
-- kommagetrennt). Ein umgestellter Schalter wirkt so nur auf danach gestartete Journeys.
ALTER TABLE orchestrator.auth_journey ADD COLUMN feature_flags VARCHAR(500) NOT NULL DEFAULT '';
