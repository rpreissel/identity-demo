# ADR-48: Vorgangszugang mit Einmalkennwort – die Einladung ist ein Keycloak-Nutzer eigener Art, nie ein Konto

**Status:** umgesetzt 2026-09-29 (Issue `DPoP-demo-uhj4`).

**Anlass.** Die Kasse lädt Personen zu einem bestimmten Vorgang ein, auch solche ohne Konto: alle
Kunden oder eine Teilmenge. Jede Person bekommt per Post ein Einmalkennwort. Damit meldet sie sich im
Web-Kanal an und erhält Access- und ID-Token, die aussehen wie die eines Kontos, aber einen Marker
tragen: Sie gelten nur für diesen einen Vorgang. Der Vorgang selbst ist ein gewöhnlicher, den
Kontoinhaber auf dem normalen Weg aufrufen. Das Kennwort gilt bis zu seiner Frist und bis der Vorgang
abgeschlossen ist; die Person kann also am nächsten Tag wiederkommen. „Einmal“ heißt „für einen
Vorgang“, nicht „einmal nutzbar“, wie beim Freischaltcode
([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)).

## Entscheidung

1. **Die Einladung gehört dem Personenverzeichnis.** Das Register stellt sie aus, verschickt das
   Kennwort per Brief und beendet sie, in Wirklichkeit über seine eigene API oder Oberfläche. Der
   Orchestrator hält keine Einladungen und keine API zum Ausstellen; `auth-invite` fragt über den Port
   `tool_api.directory.Invitations`, wie `ident-fsc` über `ActivationCodes`. Ein beendeter Vorgang
   wird als Ereignis `InvitationEnded` gemeldet, wie eine geänderte Person
   ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)).
2. **Die Id einer Einladung ist SHA-256 über `personId:KENNWORT:vorgang`**, das Kennwort ohne
   Trennzeichen und in Großbuchstaben (`Einladungen.identitaet`). Das Fachsystem bildet sie aus
   seinen eigenen Daten und beendet die Einladung damit. Person und Vorgang im Hash machen gleiche
   Kennwörter verschiedener Einladungen harmlos und zwingen einen Angreifer, jede Einladung einzeln
   zu raten. Das Kennwort hat zwölf Zeichen aus 31 (rund 59 Bit).
3. **Anmelden über das Tool `auth-invite`**, eine Anmeldung ohne bekanntes Konto
   (`ToolRole.ACCOUNT_LOOKUP_AUTH`, Faktor Besitz, höchstens `loa2`). Eingabe: KVNR oder
   Partnernummer und das Kennwort. Das Ergebnis nennt, wen es bewiesen hat, als `Subject`: ein Konto
   oder eine Einladung. Ein Fehlversuch nennt als `Attempted` die Person, sodass die Personen-Mengenbegrenzung
   zählt wie beim Freischaltcode. Das Niveau ist das der Einladung (`loa1` oder `loa2`); verlangt die
   Anmeldung mehr, bricht die Journey ab, bevor etwas gebunden wird.
4. **Der Kanal hat ein Subjekt, das kein Konto ist.** Kanal und Evidenz führen ihr Subjekt im Code
   als Sealed-Typ (`ChannelSession.subject`, `SessionEvidenceRecord.subject`: `Subject.Account` oder
   `Subject.Invitation`). Die Datenbank hält es in den Spalten `account_id` und `invitation`, von denen
   eine Prüfregel höchstens eine zulässt.
   Die Antwort an Keycloak nennt das Subjekt (`authData.subject`, `accountId` nur noch zur
   Kompatibilität). Kontofunktionen (Verfahren verwalten, Konto löschen, QR-Bestätigung) lehnen einen
   solchen Kanal ab, und er gibt keine Evidenz an einen späteren Flow-Durchlauf weiter.
5. **In Keycloak ist die Einladung ein eigener föderierter Nutzer** aus einer zweiten
   Nutzer-Federation mit fester UUID als Komponenten-Id (`INVITATION_STORAGE_COMPONENT_ID`): `f:<UUID>:<Id>`. Er trägt dieselben
   Stammdaten-Attribute wie ein Konto derselben Person und zusätzlich `orchestratorInvitation` und
   `orchestratorProcess`; zwei Attribut-Mapper im Scope `orchestrator-claims` machen daraus die Claims
   `invitation` und `process`. Er ist aktiviert, solange die Einladung offen ist, und hat weder
   Benutzernamen zum Suchen noch E-Mail noch Credential.
6. **Die Regel für jeden Fachdienst:** Trägt das Token `process`, gilt es nur für diesen Vorgang.

## In Keycloak 26.6.4 geprüft

- `StorageId.getId()` bildet `f:<componentId>:<externalId>`. Die zweite Federation hat damit einen
  eigenen `sub`-Namensraum, der sich nie mit einem Konto überschneidet, auch nicht mit dem Konto
  derselben Person.
- Sitzungen, SSO, Refresh, Logout und Brute-Force-Schutz arbeiten für einen föderierten Nutzer wie
  für jeden anderen.
- Beim Refresh verlangt `TokenManager` einen existierenden, aktiven Nutzer, sonst `invalid_grant`
  („User disabled“). Eine beendete Einladung beendet ihre Sitzungen damit spätestens beim nächsten
  Token; die Abmeldung auf `InvitationEnded` macht es sofort.
- `oidc-usermodel-attribute-mapper` lässt den Claim weg, wenn das Attribut fehlt. Konto-Tokens
  bleiben unverändert.
- **Zwei Anmeldungen im selben Browser** regelt Keycloak selbst (`AuthenticationProcessor`): Eine
  Sitzung gehört genau einem Nutzer. Ist ein Vorgangszugang aktiv, meldet jeder weitere Auth-Request
  per SSO den Einladungs-Nutzer an, jedes Token trägt den Marker. Erzwingt eine Anwendung eine neue
  Anmeldung und die Person nimmt ihr Konto, endet Keycloak mit `DIFFERENT_USER_AUTHENTICATED`; die
  bestehende Sitzung bleibt. Umgekehrt bekommt eine Vorgangsseite bei aktiver Konto-Sitzung still ein
  Konto-Token ohne Marker. Der Wechsel geht in beiden Richtungen nur über die Abmeldung.
- `LoginCompletion` lässt ein Subjekt nie die Sitzung eines anderen fortsetzen: Eine Einladung hebt
  keine Konto-Sitzung, ein Konto keine Einladungs-Sitzung.

## Erwogene Alternativen

- **Die Einladung als Verfahren an einem Konto.** Verworfen. Je eingeladener Person entstünde ein
  anmeldefähiges Konto ([ADR-46](ADR-046-konto-im-aufbau.md)). Hat die Person schon eines, träfe
  der Brief dasselbe `sub`; vergisst ein Fachdienst den Marker, hat der Briefbesitzer das Konto.
- **Die Einladungen im Orchestrator, mit System-API für das Fachsystem.** Verworfen. Der Aussteller
  ist das Register; eine Kopie im Orchestrator wäre eine zweite Wahrheit und eine zweite API.
- **Eine eigene Rolle `PROCESS_ACCESS`.** Verworfen. Die Unterschiede zur Lookup-Anmeldung stecken
  im Ergebnis (`Subject`, `Attempted`), nicht in einer Rolle; mit einem einzelnen Nachweis ergibt die
  Authenticator-Achse genau das Niveau der Einladung.
- **Ein eigener Realm** (zweiter Issuer für jeden Fachdienst), **ein eigener Client je Vorgang**
  (anderes `azp`, gegen [ADR-42](ADR-042-loa1-anmeldung-umschalten.md)) und **die Einschränkung als
  OAuth-Scope** (bestimmt der Client, nicht der Anmeldeweg; fehlt bei SSO in einen anderen Client):
  verworfen.
- **Transient Users:** EXPERIMENTAL und nur für das Identity Brokering; verworfen.
- **Ein Link statt eines Kennworts (Action Token):** möglich, weil der Einladungs-Nutzer existiert,
  für einen Brief aber unpraktisch. Bleibt der Weg für eine Einladung per E-Mail.

## Folgen und offene Punkte

- Zunächst nur der Web-Kanal. `auth-invite` ist im App-Kanal gesperrt, und die Bindung einer
  Einladung verlangt einen Web-Kanal (`ChannelType.WEB`). Für die App müsste der Grant aus
  [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) ein
  Subjekt statt `account_id` nehmen.
- Restrisiko wie beim Freischaltcode: Wer den Brief nach der legitimen Nutzung findet, kommt bis zur
  Frist oder zum Abschluss wieder hinein. Das Id-Format erlaubt einem, der ein Token sieht, das
  Kennwort dieser einen Einladung offline zu raten (rund 59 Bit); bewusst getragen.
- Die Schwachstelle ist ein Fachdienst, der `process` nicht prüft; die Regel gehört an eine zentrale
  Stelle, etwa ein Gateway.
- Offen: ob `loa2` aus dem Brief allein genügt oder ein `loa2`-Vorgang zusätzlich eine SMS verlangt,
  und ob die Einladung nach dem Vorgang eine Registrierung als Identifizierung tragen soll.

**Nachtrag 2026-09-30 (vierte Bewertung, A-1/A-2).** Die Anfrage von Keycloak nennt das Subjekt wie
die Antwort (`KcChannelUpsertRequest.subject`); ein Kanal, dem schon ein anderes Subjekt gehört,
lehnt es mit `409` ab, eine Einladung wird so nie still zum Konto. Keycloak meldet auch die
Abmeldung eines Einladungs-Nutzers (`POST …/kc/invitations/{id}/sign-outs`); sie beendet die
Web-Kanäle der Sitzung. Das Anmeldeprotokoll (`account.sign_in_log`) führt Vorgangszugänge als
Zeilen der Einladung statt eines Kontos (`ck_sign_in_log_one_subject`); sie leben nur die
Aufbewahrungsfrist des Protokolls, weil es kein Konto gibt, mit dem sie gehen könnten.

**Nachtrag 2026-09-30 (K-5): Ein Vorgangszugang wird nicht aufgewertet.** Verlangt ein späterer
Durchlauf einer Einladungssitzung ein höheres Niveau, lehnt der Orchestrator den Kanal mit `409` ab
(„Dieses Einmalkennwort genügt dem verlangten Sicherheitsniveau nicht“), ohne ihn anzulegen; die
Anmeldeseite zeigt diesen Grund. Eine Einladung bindet einen Kanal nur durch ihren eigenen Nachweis,
nie durch das Subjekt, das Keycloak nennt. Der Resume-Schritt überspringt Einladungssitzungen: Es
gibt nichts wiederherzustellen. Wer ein höheres Niveau braucht, braucht eine Einladung dieses Niveaus.
