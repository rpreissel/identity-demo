// ===================== V8__access_token_identity_only =====================

// Das AccessToken geht an jeden Fachdienst. Es trägt deshalb von den Attributen des Nutzers nur die,
// die seine Identität bezeugen: person_id und versnr. Name, Geburtsdatum, Anschrift, KVNR und
// E-Mail-Adresse stehen nur im ID-Token und in userinfo (docs/05-api.md Abschnitt 3b). Was die
// Anmeldung beschreibt (acr, amr, sub, orchestrator_account_id, process, invitation), bleibt.

// Die Scopes, deren Attribut-Mapper hier aus dem AccessToken genommen werden.
val ATTRIBUTE_SCOPES = listOf("orchestrator-claims", "profile", "email")

// Mapper, die im AccessToken bleiben: die beiden Attribute der Identität und alles, was nicht Attribut
// des Nutzers ist (V3, V6).
val KEEP_IN_ACCESS_TOKEN = setOf(
    "orchestrator-master-data-personId",
    "orchestrator-master-data-versnr",
    "orchestrator-acr-amr",
    "orchestrator-account-id",
    "orchestrator-invitation-process",
    "orchestrator-invitation-invitation",
)

step("attribute aus dem access token nehmen") {
    up {
        val changed = ATTRIBUTE_SCOPES.flatMap { scope ->
            val mappers = clientScopes().get(scopeDbId(scope)).protocolMappers
            mappers.getMappers()
                .filter { it.name !in KEEP_IN_ACCESS_TOKEN && it.config["access.token.claim"] == "true" }
                .map { mapper ->
                    mappers.update(mapper.id, mapper.apply { config = config + ("access.token.claim" to "false") })
                    "$scope/${mapper.name}"
                }
        }
        remember("changed", changed.joinToString(","))
    }
    down {
        recall("changed").split(",").filter { it.isNotEmpty() }.forEach { entry ->
            val (scope, name) = entry.split("/", limit = 2)
            val mappers = clientScopes().get(scopeDbId(scope)).protocolMappers
            mappers.getMappers().firstOrNull { it.name == name }?.let { mapper ->
                mappers.update(mapper.id, mapper.apply { config = config + ("access.token.claim" to "true") })
            }
        }
    }
}
