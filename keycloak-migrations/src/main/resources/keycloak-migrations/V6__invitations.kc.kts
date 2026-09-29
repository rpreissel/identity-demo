// ===================== V6__invitations =====================

// Anmeldung mit Einmalkennwort fuer einen Vorgang (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
// Keycloak liest Einladungen wie Konten aus dem Orchestrator, aber ueber eine eigene Nutzer-Federation
// mit fester UUID (INVITATION_STORAGE_COMPONENT_ID): ihre Nutzer heissen f:<diese UUID>:<Id> und
// teilen sich nie ein sub mit einem Konto. Zwei Attribut-Mapper tragen die Marker process und
// invitation in die Tokens; fehlt das Attribut, fehlt der Claim, Konto-Tokens bleiben also unveraendert.
// Die Einladungen selbst gehoeren dem Personenverzeichnis; der Orchestrator liest sie dort ueber einen Port.

step("einladungs-federation anlegen") {
    up {
        components().add(ComponentRepresentation().apply {
            id = INVITATION_STORAGE_COMPONENT_ID
            name = "orchestrator-invitations"
            providerId = "orchestrator-invitations"
            providerType = "org.keycloak.storage.UserStorageProvider"
            parentId = toRepresentation().id
            config = MultivaluedHashMap<String, String>().apply {
                put("priority", listOf("1"))
                put("enabled", listOf("true"))
                // Wie die Konten: hoechstens eine Minute alt, dann ist ein beendeter Vorgang sichtbar.
                put("cachePolicy", listOf("MAX_LIFESPAN"))
                put("maxLifespan", listOf("60000"))
            }
        }).close()
    }
    down {
        components().removeComponent(INVITATION_STORAGE_COMPONENT_ID)
    }
}

// upConfig() beschreibt das ganze Profil: die Attribute aus V1 (Konto-Id und Stammdaten) stehen
// deshalb wieder mit darin. Nur "admin", wie dort.
val profileBeforeInvitations = listOf(
    "orchestratorAccountId", "personId", "kvnr", "versnr", "birthDate", "streetAddress", "postalCode", "locality",
)

step("einladungs-attribute im user profile deklarieren") {
    up {
        users().userProfile().update(
            upConfig(*(profileBeforeInvitations + listOf("orchestratorInvitation", "orchestratorProcess"))
                .map { it to setOf("admin") }.toTypedArray())
        )
    }
    down {
        users().userProfile().update(upConfig(*profileBeforeInvitations.map { it to setOf("admin") }.toTypedArray()))
    }
}

// Attribut der Einladungs-Nutzer to Claim-Name im Token.
val invitationClaims = listOf(
    "orchestratorProcess" to "process",
    "orchestratorInvitation" to "invitation",
)

step("einladungs-mapper anlegen") {
    up {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        invitationClaims.forEach { (attribute, claim) ->
            mappers.createMapper(ProtocolMapperRepresentation().apply {
                name = "orchestrator-invitation-$claim"
                protocol = "openid-connect"
                protocolMapper = "oidc-usermodel-attribute-mapper"
                config = mapOf(
                    "user.attribute" to attribute,
                    "claim.name" to claim,
                    "jsonType.label" to "String",
                    "id.token.claim" to "true",
                    "access.token.claim" to "true",
                    "userinfo.token.claim" to "true",
                )
            }).close()
        }
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        invitationClaims.forEach { (_, claim) ->
            mappers.getMappers().firstOrNull { it.name == "orchestrator-invitation-$claim" }?.let { mappers.delete(it.id) }
        }
    }
}
