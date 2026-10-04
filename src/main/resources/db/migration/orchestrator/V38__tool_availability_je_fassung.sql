-- The operator's switch now holds per tool version (ADR-51), the order still per tool. The old
-- settings are dropped, not converted: without rows the demo's preset applies again (ToolDefaults).
DROP TABLE orchestrator.tool_availability;

-- A runtime kill-switch per tool version and channel type (APP / WEB). tool is the version's wire
-- form, as clients declare it in availableTools: 'enroll-sms@2'. No row = enabled.
CREATE TABLE orchestrator.tool_availability (
    tool       VARCHAR(60)  NOT NULL,
    channel    VARCHAR(32)  NOT NULL,
    enabled    BOOLEAN      NOT NULL,
    reason     VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_tool_availability PRIMARY KEY (tool, channel)
);

-- A tool's rank in one channel type's selection lists, the same for all its versions. No row =
-- unranked, behind every ranked tool.
CREATE TABLE orchestrator.tool_order (
    tool_id    VARCHAR(50)  NOT NULL,
    channel    VARCHAR(32)  NOT NULL,
    position   INTEGER      NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_tool_order PRIMARY KEY (tool_id, channel)
);
