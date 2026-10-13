// ===================== V9__backchannel_logout =====================

// Keycloak meldet jedes Sitzungsende per OIDC Back-Channel Logout an den Orchestrator, gleich wer
// abgemeldet hat: Nutzer im Browser, Admin-Konsole oder der Orchestrator selbst ueber die Admin-API.
// Beide Clients, an denen eine Sitzung haengen kann, nennen dieselbe Adresse; das logout_token
// traegt die sid (session.required), denn der Orchestrator beendet genau eine Sitzung.
// docs/05-api.md Abschnitt 3b.

fun StepContext.enableBackchannelLogout(clientId: String) {
    val client = clients().get(clientDbId(clientId))
    client.update(client.toRepresentation().apply {
        isFrontchannelLogout = false
        attributes = attributes.orEmpty() + mapOf(
            "backchannel.logout.url" to "${setup.orchestratorBaseUrl}/orchestrator/api/v1/kc/backchannel-logout",
            "backchannel.logout.session.required" to "true",
            "backchannel.logout.revoke.offline.tokens" to "false",
        )
    })
}

fun StepContext.disableBackchannelLogout(clientId: String) {
    val client = clients().get(clientDbId(clientId))
    client.update(client.toRepresentation().apply {
        attributes = attributes.orEmpty() + ("backchannel.logout.url" to "")
    })
}

step("back-channel logout fuer den browser-client") {
    up { enableBackchannelLogout(setup.browserClientId) }
    down { disableBackchannelLogout(setup.browserClientId) }
}

step("back-channel logout fuer orchestrator-app-token") {
    up { enableBackchannelLogout(setup.appTokenClientId) }
    down { disableBackchannelLogout(setup.appTokenClientId) }
}

// Der Event-Listener der Extension, der Logouts frueher selbst meldete, ist ausgebaut. Ein Realm aus
// der Zeit davor nennt ihn noch; ein Realm, das neu entsteht, nannte ihn nie.
step("alten anmeldeprotokoll-listener austragen") {
    up {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            eventsListeners = eventsListeners.orEmpty() - "orchestrator-sign-in-log"
        })
    }
    down {}
}
