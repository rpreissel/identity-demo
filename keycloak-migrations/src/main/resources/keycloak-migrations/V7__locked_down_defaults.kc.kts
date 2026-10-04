// ===================== V7__locked_down_defaults =====================

// Keycloak legt jedes Realm mit Anmeldewegen, Aktionen und Scopes an, die am Orchestrator
// vorbeiführen. Dieses Skript schließt sie: Anmeldung, Niveau und Verfahren entscheidet allein der
// Orchestrator (ADR-8, ADR-38; docs/16-lesepfad-sicherheit.md Station 4).

val OWN_REQUIRED_ACTION = "orchestrator-manage-methods"
val DENY_FLOW = "orchestrator-deny-direct-grant"
val ACCOUNT_CLIENTS = listOf("account", "account-console")

// Keycloaks eigene Aktionen ändern Credentials oder Stammdaten ohne den Orchestrator: "Passwort
// ändern" fragt das alte Passwort nicht ab und speichert bei einer Ablehnung lokal. Ein Passwort wird
// nur über die Verfahrensverwaltung geändert. Aktiv bleibt allein die eigene Aktion.
step("fremde required actions abschalten") {
    up {
        val foreign = flows().requiredActions.filter { it.isEnabled && it.alias != OWN_REQUIRED_ACTION }
        remember("disabled", foreign.joinToString(",") { it.alias })
        foreign.forEach { action ->
            action.isEnabled = false
            action.isDefaultAction = false
            flows().updateRequiredAction(action.alias, action)
        }
    }
    down {
        recall("disabled").split(",").filter { it.isNotEmpty() }.forEach { alias ->
            val action = flows().getRequiredAction(alias)
            action.isEnabled = true
            flows().updateRequiredAction(alias, action)
        }
    }
}

// Ein Direct Grant (Passwort gegen Token) umginge Kanal, Journey und Anmeldeprotokoll.
step("direct-grant flow anlegen, der immer ablehnt") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = DENY_FLOW
            description = "Direct grants are not offered: every sign-in runs through the orchestrator"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
        flows().addExecution(DENY_FLOW, mapOf("provider" to "deny-access-authenticator"))
        setRequirement(DENY_FLOW, childExecution(DENY_FLOW) { it.providerId == "deny-access-authenticator" }, "REQUIRED")
    }
    down {
        flows().deleteFlow(topFlowId(DENY_FLOW))
    }
}

// Die Bindung am Browser-Client allein lässt jeden anderen Client des Realms über Keycloaks eigenen
// Browser-Flow anmelden; dessen SSO-Sitzung genügte danach auth-cookie im Orchestrator-Flow.
step("realm-flows binden, passwort-vergessen aus") {
    up {
        val before = toRepresentation()
        remember("browserFlow", before.browserFlow.orEmpty())
        remember("directGrantFlow", before.directGrantFlow.orEmpty())
        remember("resetPasswordAllowed", (before.isResetPasswordAllowed() == true).toString())
        updateRealm {
            browserFlow = "orchestrator-browser"
            directGrantFlow = DENY_FLOW
            setResetPasswordAllowed(false)
        }
    }
    down {
        updateRealm {
            browserFlow = recall("browserFlow").ifEmpty { "browser" }
            directGrantFlow = recall("directGrantFlow").ifEmpty { "direct grant" }
            setResetPasswordAllowed(recall("resetPasswordAllowed").toBoolean())
        }
    }
}

// admin-cli ist in jedem Realm öffentlich und nimmt Passwort-Grants an; die Account-Konsole ist ein
// eigener Anmeldeweg mit Selbstbedienung an Keycloak-Credentials. Beide braucht hier niemand.
step("eingebaute clients schliessen") {
    up {
        clients().findByClientId("admin-cli").firstOrNull()?.let { rep ->
            remember("adminCliDirectGrants", (rep.isDirectAccessGrantsEnabled() == true).toString())
            rep.setDirectAccessGrantsEnabled(false)
            clients().get(rep.id).update(rep)
        }
        val disabled = ACCOUNT_CLIENTS.mapNotNull { id -> clients().findByClientId(id).firstOrNull()?.takeIf { it.isEnabled() != false } }
        remember("disabledClients", disabled.joinToString(",") { it.clientId })
        disabled.forEach { rep ->
            rep.setEnabled(false)
            clients().get(rep.id).update(rep)
        }
    }
    down {
        clients().findByClientId("admin-cli").firstOrNull()?.let { rep ->
            rep.setDirectAccessGrantsEnabled(recallOrNull("adminCliDirectGrants")?.toBoolean() ?: true)
            clients().get(rep.id).update(rep)
        }
        recall("disabledClients").split(",").filter { it.isNotEmpty() }.forEach { id ->
            clients().findByClientId(id).firstOrNull()?.let { rep ->
                rep.setEnabled(true)
                clients().get(rep.id).update(rep)
            }
        }
    }
}

// Ein Offline-Token überlebt die Fristen aus V5, die Abmeldung und das Sitzungsende des
// Orchestrators (ADR-43).
step("offline_access aus den projekt-clients nehmen") {
    up {
        val scopeId = clientScopes().findAll().firstOrNull { it.name == "offline_access" }?.id
        val removedFrom = if (scopeId == null) emptyList() else projectClients().filter { clientId ->
            val client = clients().get(clientDbId(clientId))
            client.optionalClientScopes.any { it.id == scopeId }.also { if (it) client.removeOptionalClientScope(scopeId) }
        }
        remember("removedFrom", removedFrom.joinToString(","))
    }
    down {
        val scopeId = scopeDbId("offline_access")
        recall("removedFrom").split(",").filter { it.isNotEmpty() }.forEach { clientId ->
            clients().get(clientDbId(clientId)).addOptionalClientScope(scopeId)
        }
    }
}


fun StepContext.projectClients(): List<String> = listOf(setup.browserClientId, setup.adminApiClientId, setup.appTokenClientId)
