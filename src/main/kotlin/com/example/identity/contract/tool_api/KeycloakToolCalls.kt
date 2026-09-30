package com.example.identity.contract.tool_api

/**
 * Tool calls Keycloak makes for an account it already knows, with no channel and no journey: its
 * native password form checks and replaces the password (docs/05-api.md Abschnitt 3). The module
 * owns the endpoint and the credential; the orchestrator checks who is calling and books the result,
 * as it does for a journey.
 */
interface KeycloakToolCalls {
    /**
     * Throws unless [bindingKeyRef] is Keycloak's peer-auth assertion for [accountId]. The id sits in
     * the path, which the assertion's `htu` binds; its `channel_binding` must name the same account.
     */
    fun requireKeycloakFor(accountId: Long, bindingKeyRef: String)

    /**
     * Books [outcome] of [descriptor] for [accountId]: a proof on the account's lockout, an
     * enrollment as claims and a method instance. Any other outcome is a contract error.
     */
    fun apply(accountId: Long, descriptor: ToolDescriptor, outcome: ToolOutcome)
}
