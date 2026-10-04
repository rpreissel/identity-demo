-- CHECK ohne Wertemenge (KONVENTIONEN.md): H2 macht aus "x IN ('a', 'b')" eine Suche, die ueber die
-- Sitzung vergleicht, die die Constraint uebersetzt hat - die Pool-Verbindung der Migration. Ist sie
-- ersetzt (Hikari max-lifetime), scheitert jede Pruefung mit "The database has been closed".
ALTER TABLE personenverzeichnis.einladung DROP CONSTRAINT ck_einladung_niveau;
ALTER TABLE personenverzeichnis.einladung ADD CONSTRAINT ck_einladung_niveau CHECK (CASE niveau
    WHEN 'loa1' THEN TRUE WHEN 'loa2' THEN TRUE ELSE FALSE END);
