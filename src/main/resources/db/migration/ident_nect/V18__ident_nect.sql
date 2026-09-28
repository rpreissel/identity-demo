-- Schema des Moduls `ident_nect`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS ident_nect;

-- The module half of a tool session: which Nect case this run is waiting for.
CREATE TABLE ident_nect.ident_tool_session (
    tool_session_id UUID PRIMARY KEY,
    case_id         UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ident_tool_session_created_at ON ident_nect.ident_tool_session (created_at);
