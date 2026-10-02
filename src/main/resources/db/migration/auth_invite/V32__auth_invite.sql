-- Tool-Sitzungen von auth-invite-lookup (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): nur ein
-- Existenzmarker, das Einmalkennwort wird nie gespeichert.
CREATE SCHEMA IF NOT EXISTS auth_invite;

CREATE TABLE auth_invite.invite_tool_session (
    tool_session_id UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_invite_tool_session_created_at ON auth_invite.invite_tool_session (created_at);
