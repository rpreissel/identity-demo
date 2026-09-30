-- Schema des Moduls `ident_kvnr`.
-- Die modulweiten Regeln (Schemabesitz, Typen, Namen) stehen in db/migration/KONVENTIONEN.md.

CREATE SCHEMA IF NOT EXISTS ident_kvnr;

-- =============================================================================================
-- ident_kvnr
-- =============================================================================================

CREATE TABLE ident_kvnr.ident_tool_session (
    tool_session_id UUID PRIMARY KEY,
    kvnr            VARCHAR(20),
    -- Asked only when there is no KVNR (a Partner, ADR-34).
    partner_number       VARCHAR(10),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_ident_tool_session_created_at ON ident_kvnr.ident_tool_session (created_at);
