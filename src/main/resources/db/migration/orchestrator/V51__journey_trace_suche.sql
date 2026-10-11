-- Die Admin-Ansicht sucht im Journey-Trace, statt die neuesten Einträge zu laden: Sie blättert über
-- die Starts der Journeys (eine Zeile je Journey, nach Zeit) und lädt dann die Schritte der
-- gefundenen Journeys. Ohne diese Indizes läse jede Seite die ganze Tabelle, bei 20 Mio. Konten 56
-- bis 112 Mio. Zeilen (docs/14-stand-und-weg-zur-produktion.md).
CREATE INDEX ix_journey_trace_event_type ON orchestrator.journey_trace (event_type, created_at);
CREATE INDEX ix_journey_trace_journey_id ON orchestrator.journey_trace (journey_id);
