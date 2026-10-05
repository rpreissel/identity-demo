package com.example.identity.kcmigrate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.ClientScopeResource
import org.keycloak.admin.client.resource.ClientScopesResource
import org.keycloak.admin.client.resource.ProtocolMappersResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.ClientScopeRepresentation
import org.keycloak.representations.idm.ProtocolMapperRepresentation
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Das AccessToken trägt von den Attributen nur person_id und versnr; alles andere steht nur im
 * ID-Token (docs/05-api.md Abschnitt 3b). Was die Anmeldung beschreibt, bleibt im AccessToken.
 */
class AccessTokenIdentityOnlyMigrationTest : BehaviorSpec({

    val v8 = checkNotNull(
        AccessTokenIdentityOnlyMigrationTest::class.java.getResource("/keycloak-migrations/V8__access_token_identity_only.kc.kts")
    ).readText().let { checkNotNull(MigrationFile.parse("V8__access_token_identity_only.kc.kts", it)) }

    /** Die drei Scopes mit ihren Mappern, wie Keycloak und V1 bis V6 sie anlegen. */
    class Realm {
        var rep = RealmRepresentation().apply { realm = "demo" }
        val mappersByScope: Map<String, MutableMap<String, ProtocolMapperRepresentation>> = mapOf(
            "orchestrator-claims" to listOf(
                "orchestrator-master-data-personId", "orchestrator-master-data-versnr", "orchestrator-master-data-kvnr",
                "orchestrator-master-data-birthDate", "orchestrator-master-data-streetAddress", "orchestrator-acr-amr",
                "orchestrator-account-id", "orchestrator-invitation-process", "orchestrator-invitation-invitation",
            ),
            "profile" to listOf("full name", "family name", "given name", "username"),
            "email" to listOf("email", "email verified"),
        ).mapValues { (scope, names) ->
            names.associateWith { name ->
                ProtocolMapperRepresentation().apply {
                    id = "$scope-$name"
                    this.name = name
                    config = mapOf("id.token.claim" to "true", "access.token.claim" to "true", "userinfo.token.claim" to "true")
                }
            }.toMutableMap()
        }
        val runner: MigrationRunner

        init {
            val scopes = mockk<ClientScopesResource>()
            every { scopes.findAll() } returns mappersByScope.keys.map { ClientScopeRepresentation().apply { id = "db-$it"; name = it } }
            mappersByScope.forEach { (scope, mappers) ->
                val mappersResource = mockk<ProtocolMappersResource>()
                every { mappersResource.getMappers() } answers { mappers.values.toList() }
                every { mappersResource.update(any(), any()) } answers {
                    val updated = secondArg<ProtocolMapperRepresentation>()
                    mappers[updated.name] = updated
                }
                val scopeResource = mockk<ClientScopeResource>()
                every { scopeResource.protocolMappers } returns mappersResource
                every { scopes.get("db-$scope") } returns scopeResource
            }
            val realm = mockk<RealmResource>()
            every { realm.toRepresentation() } answers { rep }
            every { realm.update(any()) } answers { rep = firstArg() }
            every { realm.clientScopes() } returns scopes
            val kc = mockk<Keycloak>()
            every { kc.realm("demo") } returns realm
            runner = MigrationRunner(kc, TEST_REALM_SETUP, listOf(v8), allowRealmReset = false)
        }

        fun inAccessToken(scope: String, name: String) = mappersByScope.getValue(scope).getValue(name).config["access.token.claim"] == "true"
        fun inIdToken(scope: String, name: String) = mappersByScope.getValue(scope).getValue(name).config["id.token.claim"] == "true"
    }

    given("die Mapper, die alle Attribute auch ins AccessToken schreiben") {
        `when`("V8 angewendet wird") {
            val realm = Realm()
            realm.runner.up()

            then("bleiben person_id und versnr im AccessToken") {
                realm.inAccessToken("orchestrator-claims", "orchestrator-master-data-personId") shouldBe true
                realm.inAccessToken("orchestrator-claims", "orchestrator-master-data-versnr") shouldBe true
            }
            then("bleibt, was die Anmeldung beschreibt, im AccessToken") {
                listOf("orchestrator-acr-amr", "orchestrator-account-id", "orchestrator-invitation-process", "orchestrator-invitation-invitation")
                    .forEach { realm.inAccessToken("orchestrator-claims", it) shouldBe true }
            }
            then("stehen KVNR, Stammdaten, Name und E-Mail nicht mehr im AccessToken") {
                realm.inAccessToken("orchestrator-claims", "orchestrator-master-data-kvnr") shouldBe false
                realm.inAccessToken("orchestrator-claims", "orchestrator-master-data-birthDate") shouldBe false
                realm.inAccessToken("orchestrator-claims", "orchestrator-master-data-streetAddress") shouldBe false
                realm.inAccessToken("profile", "full name") shouldBe false
                realm.inAccessToken("profile", "username") shouldBe false
                realm.inAccessToken("email", "email") shouldBe false
            }
            then("steht im ID-Token weiterhin alles") {
                realm.mappersByScope.forEach { (scope, mappers) ->
                    mappers.keys.forEach { realm.inIdToken(scope, it) shouldBe true }
                }
            }
        }

        `when`("V8 angewendet und wieder zurückgerollt wird") {
            val realm = Realm()
            realm.runner.up()
            realm.runner.down("8")

            then("stehen alle Attribute wieder im AccessToken") {
                realm.mappersByScope.forEach { (scope, mappers) ->
                    mappers.keys.forEach { realm.inAccessToken(scope, it) shouldBe true }
                }
            }
        }
    }
})
