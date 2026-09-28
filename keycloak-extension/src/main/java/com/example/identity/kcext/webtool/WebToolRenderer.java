package com.example.identity.kcext.webtool;

import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.provider.Provider;

/**
 * One tool's web-channel rendering; the counterpart to the App channel's {@code ToolModule.render()}.
 * Looked up by toolId; without a renderer, or when it returns null, {@code OrchestratorAuthenticator}
 * uses the generic {@code orchestrator-tool.ftl}.
 */
public interface WebToolRenderer extends Provider {

    /** Null if this tool has no bespoke UI for {@code ctx.step()} - caller falls back to the generic form. */
    Response render(LoginFormsProvider form, WebToolRenderContext ctx);

    @Override
    default void close() {
    }
}
