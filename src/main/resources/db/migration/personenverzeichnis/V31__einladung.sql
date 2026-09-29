-- Einladungen zu einem Vorgang per Einmalkennwort (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
-- Sie gehoeren dem Register wie der Freischaltcode (ADR-31): Es stellt sie aus, verschickt das
-- Kennwort per Brief und beendet sie; auth-invite fragt nur nach. Die Id ist SHA-256 ueber Person,
-- Kennwort und Vorgang, der Klartext steht allein im Brief.
CREATE TABLE personenverzeichnis.einladung (
    id               VARCHAR(64)              PRIMARY KEY,
    person_id        VARCHAR(10)              NOT NULL,
    vorgang          VARCHAR(64)              NOT NULL,
    niveau           VARCHAR(16)              NOT NULL,
    gueltig_bis      TIMESTAMP WITH TIME ZONE NOT NULL,
    ausgestellt_am   TIMESTAMP WITH TIME ZONE NOT NULL,
    abgeschlossen_am TIMESTAMP WITH TIME ZONE,
    widerrufen_am    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_einladung_person FOREIGN KEY (person_id) REFERENCES personenverzeichnis.person (id) ON DELETE CASCADE,
    CONSTRAINT ck_einladung_niveau CHECK (niveau IN ('loa1', 'loa2'))
);
CREATE INDEX ix_einladung_person_id ON personenverzeichnis.einladung (person_id);

-- Der Brief traegt einen Freischaltcode oder ein Einmalkennwort, nie beides.
ALTER TABLE personenverzeichnis.brief ALTER COLUMN freischaltcode_id DROP NOT NULL;
ALTER TABLE personenverzeichnis.brief ADD COLUMN einladung_id VARCHAR(64);
ALTER TABLE personenverzeichnis.brief ADD CONSTRAINT fk_brief_einladung
    FOREIGN KEY (einladung_id) REFERENCES personenverzeichnis.einladung (id) ON DELETE CASCADE;
ALTER TABLE personenverzeichnis.brief ADD CONSTRAINT ck_brief_ein_code
    CHECK ((freischaltcode_id IS NULL AND einladung_id IS NOT NULL) OR (freischaltcode_id IS NOT NULL AND einladung_id IS NULL));
