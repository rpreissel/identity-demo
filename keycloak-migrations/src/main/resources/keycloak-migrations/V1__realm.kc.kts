// Das gesamte Realm in einer Datei, gegliedert in die Abschnitte V1..V10. Eine Datei genuegt, weil
// der Runner ohnehin nur alles zusammen aufbaut oder zurueckrollt (siehe MigrationRunner).
//
// Nichts Dynamisches steht hier: Realm-Name, Client-Ids, Redirect-URIs und URLs kommen aus setup
// ([RealmSetup], ausgewaehlt ueber keycloak-setup.variant in application-keycloak.yml).
// Client-Secrets gibt es keine: beide Orchestrator-Clients weisen sich per private_key_jwt aus.


// ===================== V1__realm_base =====================

// Realm-Einstellungen, User Profile und Events. Anlegen und Löschen des Realms übernimmt
// MigrationRunner.

step("realm einstellungen setzen") {
    up {
        updateRealm {
            displayName = setup.realmDisplayName
            // Unter diesem Theme leben die Formulare der keycloak-extension.
            loginTheme = setup.loginTheme
            // auth-username-password-form (LoA1) akzeptiert auch die E-Mail-Adresse.
            setLoginWithEmailAllowed(true)
            // Die Login-Sprache bestimmt die Sprache der Orchestrator-Texte (ADR-33).
            setInternationalizationEnabled(true)
            setSupportedLocales(setOf("de", "en"))
            setDefaultLocale("de")
            // Schutz gegen Passwort-Raten: nach 5 Fehlversuchen in 15 Minuten wartet der User bis
            // zu 15 Minuten, nie dauerhaft gesperrt. Der Orchestrator zaehlt dieselben Fehlversuche
            // zusaetzlich auf seine eigene Kontosperre.
            setBruteForceProtected(true)
            setFailureFactor(5)
            setWaitIncrementSeconds(60)
            setMaxFailureWaitSeconds(900)
            setMaxDeltaTimeSeconds(900)
            setPermanentLockout(false)
        }
    }
    down {
        updateRealm {
            displayName = null
            loginTheme = null
            setBruteForceProtected(false)
            setLoginWithEmailAllowed(false)
            setInternationalizationEnabled(false)
            setSupportedLocales(emptySet())
            setDefaultLocale(null)
        }
    }
}

// Keycloaks deklaratives User Profile verwirft jedes nicht deklarierte User-Attribut, ohne Fehler.
// Diese Ressource beschreibt das gesamte Profil (upConfig).
step("user profile konfigurieren") {
    up {
        users().userProfile().update(upConfig("orchestratorAccountId" to setOf("admin")))
    }
    down {
        users().userProfile().update(upConfig())
    }
}

step("events aktivieren") {
    up {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            setEventsEnabled(true)
            eventsListeners = listOf("jboss-logging")
        })
    }
    down {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            setEventsEnabled(false)
            eventsListeners = listOf("jboss-logging")
        })
    }
}


// ===================== V2__browser_authentication_flow =====================

// Der "orchestrator-browser"-Flow-Baum. Keycloak vergibt die Priority innerhalb einer Flow-Ebene
// nach Anlage-Reihenfolge, deshalb zählt die Reihenfolge der Schritte. Struktur nach Keycloaks
// LoA-Subflows: LoA1 = natives Keycloak-Passwort (sofort an den Orchestrator gemeldet) oder, per
// Schalter im Orchestrator, dessen selectMethod (ADR-42); LoA2 = orchestrator-eigenes selectMethod.

step("orchestrator-browser flow anlegen") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = "orchestrator-browser"
            description = "Browser flow with LoA conditions driven by the orchestrator's kc-facade"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
    }
    down {
        flows().deleteFlow(topFlowId("orchestrator-browser"))
    }
}

// Im eigenen Subflow, damit Keycloaks AuthenticationFlowCallback greift: onTopFlowSuccess feuert
// nur für eine Execution, deren Parent ein Subflow ist.
step("orchestrator-resume-wrapper subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser", mapOf(
            "alias" to "orchestrator-resume-wrapper",
            "type" to "basic-flow",
            "description" to "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers",
        ))
        setRequirement("orchestrator-browser", childExecution("orchestrator-browser") { it.displayName == "orchestrator-resume-wrapper" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser") { it.displayName == "orchestrator-resume-wrapper" })
    }
}

step("orchestrator-resume execution anlegen") {
    up {
        flows().addExecution("orchestrator-resume-wrapper", mapOf("provider" to "orchestrator-resume-authenticator"))
        setRequirement(
            "orchestrator-resume-wrapper",
            childExecution("orchestrator-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" })
    }
}

// Keycloak verschluckt einen ALTERNATIVE-Zweig neben REQUIRED auf derselben Ebene. Deshalb ein
// REQUIRED-Schritt, dann ein REQUIRED-Subflow mit der eigentlichen ALTERNATIVE-Gruppe.
step("orchestrator-browser-forms subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser", mapOf(
            "alias" to "orchestrator-browser-forms",
            "type" to "basic-flow",
            "description" to "Cookie SSO reuse vs. interactive auth, tried as alternatives",
        ))
        setRequirement("orchestrator-browser", childExecution("orchestrator-browser") { it.displayName == "orchestrator-browser-forms" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser") { it.displayName == "orchestrator-browser-forms" })
    }
}

step("browser-cookie execution anlegen") {
    up {
        flows().addExecution("orchestrator-browser-forms", mapOf("provider" to "auth-cookie"))
        setRequirement("orchestrator-browser-forms", childExecution("orchestrator-browser-forms") { it.providerId == "auth-cookie" }, "ALTERNATIVE")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms") { it.providerId == "auth-cookie" })
    }
}

step("orchestrator-auth-flow subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser-forms", mapOf(
            "alias" to "orchestrator-auth-flow",
            "type" to "basic-flow",
            "description" to "LoA-aware branches, each driven by the OrchestratorAuthenticator",
        ))
        setRequirement(
            "orchestrator-browser-forms",
            childExecution("orchestrator-browser-forms") { it.displayName == "orchestrator-auth-flow" },
            "ALTERNATIVE",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms") { it.displayName == "orchestrator-auth-flow" })
    }
}

// ── LoA 1: zwei Belegungen, der Orchestrator schaltet zur Laufzeit zwischen ihnen um, indem er die
// Requirements tauscht (KeycloakLoa1Login, ADR-42). Angelegt wird der Stand, der ohne Schalter gilt:
// die Verfahrensauswahl des Orchestrators REQUIRED, Keycloaks Passwort DISABLED.

step("orchestrator-loa-1 subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow", mapOf(
            "alias" to "orchestrator-loa-1",
            "type" to "basic-flow",
            "description" to "LoA-1 branch: native Keycloak username/password, or the orchestrator's method selection (switched at runtime)",
        ))
        setRequirement("orchestrator-auth-flow", childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-1" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-1" })
    }
}

step("loa-1 condition execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-1", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-condition"
            config = mapOf("loa-condition-level" to "1", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "conditional-level-of-authentication" })
    }
}

// Die andere Belegung: natives Keycloak-Passwort. Die accountId liefert die Nutzer-Federation
// (ADR-38); die Anmeldung wird sofort an den Orchestrator gemeldet, damit LoA-2-Kandidaten
// "password" ausschließen.
step("loa-1 password execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "auth-username-password-form"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "auth-username-password-form" }
        setRequirement("orchestrator-loa-1", id, "DISABLED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "auth-username-password-form" })
    }
}

step("loa-1 report-password execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "orchestrator-update-authenticator"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-update-authenticator" }
        setRequirement("orchestrator-loa-1", id, "DISABLED")
        // Muss zu NativeAuthenticatorRegistry.kt's "kc-password-form" passen; dort werden
        // method/maxAcr/factorTypes aufgelöst.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-report-password"
            config = mapOf("nativeToolId" to "kc-password-form")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-update-authenticator" })
    }
}

// Die Belegung ohne Schalter: die Verfahrensauswahl des Orchestrators, darunter auth-qr-lookup.
step("loa-1 orchestrator execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-loa-1", id, "REQUIRED")
        // Ohne toolId: web_select_method zeigt alle ACCOUNT_LOOKUP_AUTH-Kandidaten.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-orchestrator"
            config = mapOf("targetAcr" to "loa1")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-authenticator" })
    }
}

// ── LoA 2: Step-up, nur kontospezifische Kandidaten ──────────────────────────────────────────────

step("orchestrator-loa-2 subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow", mapOf(
            "alias" to "orchestrator-loa-2",
            "type" to "basic-flow",
            "description" to "LoA-2 branch: orchestrator step-up (account-specific candidates)",
        ))
        setRequirement("orchestrator-auth-flow", childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-2" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-2" })
    }
}

step("loa-2 condition execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-2") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-2", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-condition"
            config = mapOf("loa-condition-level" to "2", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2") { it.providerId == "conditional-level-of-authentication" })
    }
}

step("loa-2 orchestrator execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-loa-2") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-loa-2", id, "REQUIRED")
        // Die eigene Level-Nummer als Orchestrator-ACR.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-orchestrator"
            config = mapOf("targetAcr" to "loa2")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2") { it.providerId == "orchestrator-authenticator" })
    }
}


// ===================== V3__orchestrator_claims_scope =====================

// Der orchestrator-claims-Scope mit OrchestratorAcrAmrMapper und dem account-id-Mapper. Als eigener
// Default-Client-Scope, damit jeder Client (V4, V5, V8) dasselbe Verhalten bekommt.

step("orchestrator-claims scope anlegen") {
    up {
        clientScopes().create(ClientScopeRepresentation().apply {
            name = "orchestrator-claims"
            protocol = "openid-connect"
        }).close()
    }
    down {
        clientScopes().get(scopeDbId("orchestrator-claims")).remove()
    }
}

// Hängt OrchestratorAcrAmrMapper.PROVIDER_ID an den Scope, nicht an einen Client.
step("orchestrator-acr-amr mapper anlegen") {
    up {
        clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers.createMapper(ProtocolMapperRepresentation().apply {
            name = "orchestrator-acr-amr"
            protocol = "openid-connect"
            protocolMapper = "orchestrator-acr-amr-mapper"
            // Die Basisklasse ruft setClaim nur, wenn diese beiden Keys "true" sind.
            config = mapOf("access.token.claim" to "true", "id.token.claim" to "true")
        }).close()
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        mappers.getMappers().first { it.name == "orchestrator-acr-amr" }.let { mappers.delete(it.id) }
    }
}

// Traegt die accountId als Claim orchestrator_account_id in die Tokens - aus dem Attribut
// orchestratorAccountId, das die Nutzer-Federation liefert (ADR-38).
step("orchestrator-account-id mapper anlegen") {
    up {
        clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers.createMapper(ProtocolMapperRepresentation().apply {
            name = "orchestrator-account-id"
            protocol = "openid-connect"
            protocolMapper = "oidc-usermodel-attribute-mapper"
            config = mapOf(
                "user.attribute" to "orchestratorAccountId",
                "claim.name" to "orchestrator_account_id",
                "jsonType.label" to "String",
                "id.token.claim" to "true",
                "access.token.claim" to "true",
                "userinfo.token.claim" to "true",
            )
        }).close()
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        mappers.getMappers().first { it.name == "orchestrator-account-id" }.let { mappers.delete(it.id) }
    }
}


// ===================== V4__browser_client =====================

// Der Browser-Client des Web-Kanals. Braucht den Flow aus V2 und den Scope aus V3. Getrennt von
// orchestrator-admin (V5): nie Service-Account, nie Admin-Rechte. PUBLIC + PKCE (S256), weil die
// Web-Demo den Code direkt im Browser tauscht; ein Secret stünde sonst im Browser-Bundle.

step("browser client anlegen") {
    up {
        val flowId = flows().getFlows().first { it.alias == "orchestrator-browser" }.id
        clients().create(ClientRepresentation().apply {
            clientId = setup.browserClientId
            name = setup.browserClientId
            setPublicClient(true)
            setStandardFlowEnabled(true)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(false)
            redirectUris = setup.browserRedirectUris
            // "+" heisst bei Keycloak: genau die Origins der redirectUris oben.
            webOrigins = listOf("+")
            attributes = mapOf("pkce.code.challenge.method" to "S256")
            authenticationFlowBindingOverrides = mapOf("browser" to flowId)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.browserClientId)).remove()
    }
}

step("browser default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V5__orchestrator_admin_client =====================

// Der Admin-Client des Orchestrators. Sein Service Account braucht nur manage-users: eine Sitzung
// beenden und nach einer Kontoloeschung aufraeumen. Konten liest Keycloak selbst ueber die
// Nutzer-Federation (ADR-38).

fun StepContext.serviceAccountUserId(): String = clients().get(clientDbId(setup.adminApiClientId)).serviceAccountUser.id

/**
 * Client-Attribute fuer private_key_jwt: Keycloak holt den oeffentlichen Schluessel des
 * Orchestrators bei jedem unbekannten `kid` unter dieser Adresse ab, statt ihn hier als Zertifikat
 * eingemauert zu tragen - derselbe Weg, den der Orchestrator umgekehrt fuer Keycloaks
 * Peer-Auth-Schluessel nimmt. Jeder Client hat sein eigenes JWKS und damit seinen eigenen Schluessel:
 * Der Schluessel des rechtlosen Token-Clients darf nicht fuer den Admin-Client taugen.
 */
fun orchestratorClientJwtAttributes(setup: RealmSetup, clientId: String) = mapOf(
    "use.jwks.url" to "true",
    "jwks.url" to "${setup.orchestratorBaseUrl}/orchestrator/api/v1/kc/client-jwks/$clientId/.well-known/jwks.json",
    // Keycloak-Default ist RS256; der Orchestrator signiert mit EC P-256 wie alles andere hier.
    "token.endpoint.auth.signing.alg" to "ES256",
)

step("orchestrator-admin client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = setup.adminApiClientId
            name = setup.adminApiClientId
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(true)
            // private_key_jwt statt Client-Secret (ADR-25): Keycloak holt den oeffentlichen
            // Schluessel unter jwks.url ab. Spiegelbild der Assertion, mit der sich Keycloak beim
            // Orchestrator ausweist (ADR-7).
            clientAuthenticatorType = "client-jwt"
            attributes = orchestratorClientJwtAttributes(setup, setup.adminApiClientId)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.adminApiClientId)).remove()
    }
}

step("service-account manage-users Rolle zuweisen") {
    up {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("manage-users").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).add(listOf(role))
    }
    down {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("manage-users").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).remove(listOf(role))
    }
}

// Der "orchestrator-claims"-Scope auch hier, sonst läuft OrchestratorAcrAmrMapper nie für Tokens,
// die als dieser Client gemintet werden.
step("orchestrator-admin default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.adminApiClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.adminApiClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V6__orchestrator_password_federation =====================

// Die orchestrator-Komponente hat zwei Aufgaben:
// 1. Sie leitet das "password"-Credential an OrchestratorStorageProvider; kein Passwort liegt in
//    Keycloak, wie bei einer LDAP-Federation.
// 2. Ihre Config-Properties sind die Konfiguration der ganzen Extension (OrchestratorSettings),
//    sichtbar und änderbar in der Admin-Console und Teil des Realm-Exports (ADR-25).

step("orchestrator-komponente anlegen") {
    up {
        components().add(ComponentRepresentation().apply {
            name = "orchestrator"
            providerId = "orchestrator"
            providerType = "org.keycloak.storage.UserStorageProvider"
            config = MultivaluedHashMap<String, String>().apply {
                put("priority", listOf("0"))
                put("enabled", listOf("true"))
                put("orchestratorBaseUrl", listOf(setup.orchestratorBaseUrl))
                put("publicOrchestratorBaseUrl", listOf(setup.publicOrchestratorBaseUrl))
                put("peerAuthIssuer", listOf(setup.peerAuthIssuer))
                put("peerAuthAudience", listOf(setup.peerAuthAudience))
            }
        }).close()
    }
    down {
        val id = components().query().first {
            it.name == "orchestrator" && it.providerType == "org.keycloak.storage.UserStorageProvider"
        }.id
        components().removeComponent(id)
    }
}


// ===================== V7__registration_authentication_flow =====================

// Registrierung über den Web-Kanal (docs/04-orchestrierung.md #2): kein natives Formular, die
// einzige Execution ist der OrchestratorAuthenticator mit intent=register. Keine LoA-Subflows, die
// REGISTER-Journey fährt die ganze Kette selbst. Der resume-wrapper läuft trotzdem zuerst, damit ein
// Seitenreload denselben Kanal fortsetzt; dafür braucht er seinen eigenen Subflow.

step("orchestrator-registration flow anlegen") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = "orchestrator-registration"
            description = "Registration driven entirely by the orchestrator's kc-facade (intent=register)"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
    }
    down {
        flows().deleteFlow(topFlowId("orchestrator-registration"))
    }
}

step("orchestrator-registration-resume-wrapper subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-registration", mapOf(
            "alias" to "orchestrator-registration-resume-wrapper",
            "type" to "basic-flow",
            "description" to "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers",
        ))
        setRequirement(
            "orchestrator-registration",
            childExecution("orchestrator-registration") { it.displayName == "orchestrator-registration-resume-wrapper" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(
            childExecution("orchestrator-registration") { it.displayName == "orchestrator-registration-resume-wrapper" }
        )
    }
}

step("orchestrator-registration-resume execution anlegen") {
    up {
        flows().addExecution("orchestrator-registration-resume-wrapper", mapOf("provider" to "orchestrator-resume-authenticator"))
        setRequirement(
            "orchestrator-registration-resume-wrapper",
            childExecution("orchestrator-registration-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(
            childExecution("orchestrator-registration-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" }
        )
    }
}

step("orchestrator-registration execution anlegen") {
    up {
        flows().addExecution("orchestrator-registration", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-registration") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-registration", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-registration-intent"
            config = mapOf("intent" to "register")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-registration") { it.providerId == "orchestrator-authenticator" })
    }
}

// Bindet den Flow als Registrierungs-Flow und schaltet die Selbstregistrierung frei (sonst kein
// "Registrieren"-Link). Revert bindet auf Keycloaks "registration"-Flow zurück, nicht auf null.
step("registrierung freischalten und an orchestrator-registration binden") {
    up {
        updateRealm {
            setRegistrationAllowed(true)
            registrationFlow = "orchestrator-registration"
        }
    }
    down {
        updateRealm {
            setRegistrationAllowed(false)
            registrationFlow = "registration"
        }
    }
}


// ===================== V8__orchestrator_app_client =====================

// Eigener Client für den Custom-Grant urn:identity-demo:account-token (App-Kanal): Er darf nur Tokens
// für App-Kanal-Konten minten, nie die Admin-REST-API aufrufen. Getrennt von orchestrator-admin
// (V5), damit dieser Pfad keine Admin-Rechte mitbekommt.

step("orchestrator-app-token client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = setup.appTokenClientId
            name = setup.appTokenClientId
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            // Kein Service Account: der Grant mintet für die angegebene account_id, nicht für den
            // Client selbst.
            setServiceAccountsEnabled(false)
            // Wie orchestrator-admin: signierte Client-Assertion statt Secret (ADR-25).
            clientAuthenticatorType = "client-jwt"
            attributes = orchestratorClientJwtAttributes(setup, setup.appTokenClientId)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.appTokenClientId)).remove()
    }
}

// Der "orchestrator-claims"-Scope, sonst laeuft OrchestratorAcrAmrMapper nie fuer diese Tokens.
step("orchestrator-app-token default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V9__manage_methods_required_action =====================

// Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/05-api.md, "Anmeldeverfahren
// verwalten im Web-Kanal"): eine Required Action, erreichbar per kc_action=orchestrator-manage-methods
// über den bestehenden Client und Flow. defaultAction=false: nie automatisch erzwungen.

step("orchestrator-manage-methods required action registrieren") {
    up {
        flows().registerRequiredAction(RequiredActionProviderSimpleRepresentation().apply {
            providerId = "orchestrator-manage-methods"
            name = "Anmeldeverfahren verwalten"
        })
        val rep = flows().getRequiredAction("orchestrator-manage-methods")
        rep.isEnabled = true
        rep.isDefaultAction = false
        flows().updateRequiredAction("orchestrator-manage-methods", rep)
    }
    down {
        flows().removeRequiredAction("orchestrator-manage-methods")
    }
}


// ===================== V10__stammdaten_claim_mappers =====================

// Die Stammdaten-Attribute aus masterDataAttributes() (ADR-38) als Token-Claims, im
// orchestrator-claims-Scope wie V3. Die Attributnamen sind woertlich die Schluessel aus
// masterDataAttributes(): ein abweichender Name liesse den Claim still leer. personId/kvnr gibt es
// nur fuer ein Konto mit PERSON_ID-Anker. Fehlende Werte laesst der Mapper weg.

// Attribut (masterDataAttributes()) to Claim-Name im Token.
val masterDataClaims = listOf(
    "personId" to "person_id",
    "kvnr" to "kvnr",
    // Versicherungsnummer - nur fuer bei uns Versicherte (ADR-34).
    "versnr" to "versnr",
    "birthDate" to "birth_date",
    // Strasse und Hausnummer in einer Zeile, wie eID und PID sie bezeugen (AttributeType.STREET_ADDRESS).
    "streetAddress" to "street_address",
    "postalCode" to "postal_code",
    "locality" to "locality",
)

// Ohne diesen Schritt waeren die Mapper wirkungslos: das deklarative User Profile verwirft jedes
// nicht deklarierte Attribut still. upConfig() beschreibt das gesamte Profil, deshalb steht
// orchestratorAccountId mit darin. Nur "admin": die Werte kommen aus der Nutzer-Federation, der
// Nutzer soll seine Stammdaten weder sehen noch aendern, sie gehoeren dem Register.
step("stammdaten im user profile deklarieren") {
    up {
        users().userProfile().update(
            upConfig(
                *(listOf("orchestratorAccountId") + masterDataClaims.map { it.first })
                    .map { it to setOf("admin") }
                    .toTypedArray()
            )
        )
    }
    down {
        users().userProfile().update(upConfig("orchestratorAccountId" to setOf("admin")))
    }
}

step("stammdaten mapper anlegen") {
    up {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        masterDataClaims.forEach { (attribute, claim) ->
            mappers.createMapper(ProtocolMapperRepresentation().apply {
                name = "orchestrator-master-data-$attribute"
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
        masterDataClaims.forEach { (attribute, _) ->
            mappers.getMappers().first { it.name == "orchestrator-master-data-$attribute" }.let { mappers.delete(it.id) }
        }
    }
}
