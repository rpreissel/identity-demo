// ===================== V5__realm_lifetimes =====================

// Fristen, auf denen ADR-9 und ADR-43 aufbauen, stehen im Realm selbst statt als Keycloak-Default
// (docs/07-betrieb.md Abschnitt 3). Die Werte gleichen denen, die TokenService ohne Keycloak
// verwendet: 5 Minuten AccessToken, 30 Minuten Leerlauf.

// Ein einmal erreichtes loa2 traegt 30 Minuten, so lange wie der Leerlauf der Sitzung; danach verlangt das Condition-LoA bei einer
// Anfrage mit acr_values=2 einen frischen Nachweis, statt ihn aus der SSO-Sitzung zu uebernehmen.
step("loa-2 max-age verkuerzen") {
    up {
        val configId = loa2ConditionConfigId()
        remember("loaMaxAge", flows().getAuthenticatorConfig(configId).config["loa-max-age"].orEmpty())
        setLoa2MaxAge(configId, "1800")
    }
    down {
        recallOrNull("loaMaxAge")?.takeIf { it.isNotEmpty() }?.let { setLoa2MaxAge(loa2ConditionConfigId(), it) }
    }
}

step("token- und sitzungsdauern setzen") {
    up {
        val before = toRepresentation()
        remember("accessTokenLifespan", before.accessTokenLifespan?.toString().orEmpty())
        remember("ssoSessionIdleTimeout", before.ssoSessionIdleTimeout?.toString().orEmpty())
        remember("ssoSessionMaxLifespan", before.ssoSessionMaxLifespan?.toString().orEmpty())
        remember("sslRequired", before.sslRequired.orEmpty())
        updateRealm {
            accessTokenLifespan = 300
            ssoSessionIdleTimeout = 1800
            ssoSessionMaxLifespan = 36000
            // https fuer jeden Zugriff ausser aus privaten Netzen (lokale Demo, Podman-Netz).
            sslRequired = "external"
        }
    }
    down {
        updateRealm {
            accessTokenLifespan = recallOrNull("accessTokenLifespan")?.toIntOrNull()
            ssoSessionIdleTimeout = recallOrNull("ssoSessionIdleTimeout")?.toIntOrNull()
            ssoSessionMaxLifespan = recallOrNull("ssoSessionMaxLifespan")?.toIntOrNull()
            sslRequired = recallOrNull("sslRequired")?.ifEmpty { null }
        }
    }
}

fun StepContext.loa2ConditionConfigId(): String =
    flows().getExecutions("orchestrator-loa-2")
        .first { it.providerId == "conditional-level-of-authentication" }
        .authenticationConfig

fun StepContext.setLoa2MaxAge(configId: String, maxAge: String) {
    val rep = flows().getAuthenticatorConfig(configId)
    rep.config = rep.config.orEmpty() + ("loa-max-age" to maxAge)
    flows().updateAuthenticatorConfig(configId, rep)
}
