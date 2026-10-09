# ADR-48: Vorgangszugang mit Einmalkennwort – die Einladung ist ein Keycloak-Nutzer eigener Art, nie ein Konto

**Status:** umgesetzt 2026-09-29 (Issue `DPoP-demo-uhj4`).

**Anlass.** Die Kasse lädt Personen zu einem bestimmten Vorgang ein. Das können alle Kunden sein
oder nur ein Teil von ihnen, und auch Personen ohne Konto gehören dazu. Jede eingeladene Person
bekommt per Post ein Einmalkennwort. Damit meldet sie sich auf der Website an, im
[Web-Kanal](../glossar/glossar.md). Sie erhält dann ein Access-Token und ein ID-Token. Das sind die
signierten Nachweise, die Keycloak nach einer Anmeldung ausstellt und die ein Fachdienst prüft.

Diese Tokens sehen aus wie die eines Kontos, enthalten aber eine zusätzliche Kennzeichnung: Sie
gelten nur für diesen einen Vorgang. Der Vorgang selbst ist ein gewöhnlicher Vorgang, den
Kontoinhaber auf dem normalen Weg aufrufen.

Das Kennwort gilt bis zu seiner Frist und bis der Vorgang abgeschlossen ist. Die Person kann also am
nächsten Tag wiederkommen. „Einmal“ heißt hier „für einen Vorgang“, nicht „nur einmal nutzbar“. Das
ist genauso wie beim Freischaltcode
([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)).

Diese ADR legt fest, wo die Einladungen verwaltet werden, wie die Anmeldung damit abläuft und wie
eine solche Anmeldung in Orchestrator und Keycloak dargestellt wird, ohne dass dafür ein Konto
entsteht.

## Entscheidung

1. **Die Einladung gehört dem Personenverzeichnis.** Das
   [Personenverzeichnis](../glossar/glossar.md) ist das System mit den Stammdaten der Versicherung.
   Es stellt die Einladung aus, verschickt das Kennwort per Brief und beendet die Einladung. In der
   Wirklichkeit geschieht das über seine eigene API oder Oberfläche.

   Der Orchestrator speichert keine Einladungen und bietet keine API zum Ausstellen an. Das Tool
   `auth-invite-lookup` fragt über den [Port](../glossar/glossar.md) `tool_api.directory.Invitations`,
   also über eine fest vereinbarte Schnittstelle zum Fremdsystem. Genauso fragt `ident-fsc` über
   `ActivationCodes`. Ist ein Vorgang beendet, meldet das Personenverzeichnis das als Ereignis
   `InvitationEnded`, so wie es auch eine geänderte Person meldet
   ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)).
2. **Die Id einer Einladung ist SHA-256 über `personId:KENNWORT:vorgang`.** Das Kennwort steht dabei
   ohne Trennzeichen und in Großbuchstaben (`Einladungen.identitaet`). Das Fachsystem bildet die Id
   aus seinen eigenen Daten und beendet die Einladung über diese Id.

   Person und Vorgang sind mit im Hash. Dadurch richten gleiche Kennwörter in verschiedenen
   Einladungen keinen Schaden an, und ein Angreifer muss jede Einladung einzeln raten. Das Kennwort
   hat zwölf Zeichen aus einem Vorrat von 31 Zeichen, das sind rund 59 Bit.
3. **Die Anmeldung läuft über das Tool `auth-invite-lookup`.** Es ist eine Anmeldung, bei der das
   Konto vorher nicht bekannt ist (`ToolRole.ACCOUNT_LOOKUP_AUTH`). Es zählt als Faktor Besitz und
   erreicht höchstens das Niveau `loa2`. Die Eingabe ist die KVNR oder die Partnernummer zusammen mit
   dem Kennwort.

   Das Ergebnis nennt als `Subject`, wen das Tool bewiesen hat: ein Konto oder eine Einladung. Ein
   Fehlversuch nennt als `Attempted` die Person. Dadurch zählt die
   [Mengenbegrenzung](../glossar/glossar.md) je Person genauso wie beim Freischaltcode.

   Das erreichte Niveau ist das der Einladung (`loa1` oder `loa2`). Verlangt die Anmeldung ein
   höheres Niveau, bricht die Journey ab, bevor etwas gebunden wird.
4. **Der Kanal hat ein Subjekt, das kein Konto ist.** Ein [Kanal](../glossar/glossar.md) ist die
   Verbindung eines Nutzers zum Orchestrator. Das [Subjekt](../glossar/glossar.md) sagt, wem ein
   angemeldeter Kanal gehört. Kanal und Evidenz (die gespeicherten Nachweise der Sitzung) führen ihr
   Subjekt im Code als Sealed-Typ, also als Typ mit einer festen Auswahl von Varianten
   (`ChannelSession.subject`, `SessionEvidenceRecord.subject`: `Subject.Account` oder
   `Subject.Invitation`).

   Die Datenbank speichert das Subjekt in den Spalten `account_id` und `invitation`. Eine Prüfregel
   lässt höchstens eine der beiden zu. Die Antwort an Keycloak nennt das Subjekt
   (`authData.subject`). `accountId` gibt es dort nur noch aus Kompatibilitätsgründen.

   Kontofunktionen wie Verfahren verwalten, Konto löschen oder QR-Bestätigung lehnen einen solchen
   Kanal ab. Er gibt auch keine Evidenz an einen späteren Flow-Durchlauf weiter.
5. **In Keycloak ist die Einladung ein eigener föderierter Nutzer.** Ein föderierter Nutzer ist ein
   Nutzer, den Keycloak nicht selbst speichert, sondern bei einer externen Quelle nachliest, der
   Nutzer-Federation. Für Einladungen gibt es eine zweite Nutzer-Federation mit fester UUID als
   Komponenten-Id (`INVITATION_STORAGE_COMPONENT_ID`). Die Nutzer-Id hat die Form `f:<UUID>:<Id>`.

   Der Einladungs-Nutzer hat dieselben Stammdaten-Attribute wie ein Konto derselben Person. Dazu
   kommen die Attribute `orchestratorInvitation` und `orchestratorProcess`. Zwei Attribut-Mapper im
   Scope `orchestrator-claims` machen daraus die Claims `invitation` und `process`, also Einträge im
   Token. Der Nutzer ist aktiviert, solange die Einladung offen ist. Er hat weder einen
   Benutzernamen, über den man ihn suchen könnte, noch eine E-Mail-Adresse noch ein Credential.
6. **Die Regel für jeden Fachdienst:** Enthält das Token den Claim `process`, gilt es nur für diesen
   Vorgang.

## In Keycloak 26.6.4 geprüft

- `StorageId.getId()` bildet `f:<componentId>:<externalId>`. Die zweite Federation hat damit einen
  eigenen Namensraum für `sub`, die Kennung des Nutzers im Token. Er überschneidet sich nie mit
  einem Konto, auch nicht mit dem Konto derselben Person.
- Sitzungen, SSO (die Anmeldung über mehrere Anwendungen mit einer Sitzung), Refresh, Logout und der
  Schutz gegen Brute-Force-Angriffe funktionieren für einen föderierten Nutzer wie für jeden anderen.
  Den Brute-Force-Schutz von Keycloak hat
  [ADR-58](ADR-058-keycloak-fuehrt-keine-eigenen-anmeldeschritte.md) später abgeschaltet.
- Beim Refresh verlangt `TokenManager` einen existierenden, aktiven Nutzer. Sonst antwortet er mit
  `invalid_grant` („User disabled“). Eine beendete Einladung beendet ihre Sitzungen damit spätestens
  beim nächsten Token. Die Abmeldung auf das Ereignis `InvitationEnded` beendet sie sofort.
- `oidc-usermodel-attribute-mapper` lässt den Claim weg, wenn das Attribut fehlt. Die Tokens von
  Konten bleiben also unverändert.
- **Zwei Anmeldungen im selben Browser** regelt Keycloak selbst (`AuthenticationProcessor`). Eine
  Sitzung gehört genau einem Nutzer. Daraus folgt:
  - Ist ein Vorgangszugang aktiv, meldet jeder weitere Auth-Request per SSO den Einladungs-Nutzer
    an. Jedes Token enthält dann die Kennzeichnung.
  - Erzwingt eine Anwendung eine neue Anmeldung und meldet sich die Person dabei mit ihrem Konto an,
    bricht Keycloak mit `DIFFERENT_USER_AUTHENTICATED` ab. Die bestehende Sitzung bleibt.
  - Umgekehrt bekommt eine Vorgangsseite bei aktiver Konto-Sitzung ohne Rückfrage ein Konto-Token
    ohne Kennzeichnung.

  Der Wechsel zwischen beiden ist in jede Richtung nur über die Abmeldung möglich.
- `LoginCompletion` lässt ein Subjekt nie die Sitzung eines anderen fortsetzen. Eine Einladung setzt
  keine Konto-Sitzung fort und ein Konto keine Einladungs-Sitzung.

## Erwogene Alternativen

- **Die Einladung als Verfahren an einem Konto.** Verworfen. Für jede eingeladene Person entstünde
  ein Konto, in das man sich anmelden kann ([ADR-46](ADR-046-konto-im-aufbau.md)). Hat die Person
  schon ein Konto, hätte die Anmeldung mit dem Brief dasselbe `sub`. Vergisst dann ein Fachdienst,
  die Kennzeichnung zu prüfen, kann jeder, der den Brief besitzt, das Konto benutzen.
- **Die Einladungen im Orchestrator, mit einer System-API für das Fachsystem.** Verworfen. Die
  Einladungen stellt das Personenverzeichnis aus. Eine Kopie im Orchestrator wäre ein zweiter
  Datenbestand, der mit dem ersten übereinstimmen müsste, und eine zweite API.
- **Eine eigene Rolle `PROCESS_ACCESS`.** Verworfen. Die Unterschiede zur Lookup-Anmeldung liegen im
  Ergebnis (`Subject`, `Attempted`), nicht in einer Rolle. Mit einem einzelnen Nachweis ergibt die
  Bewertung der Anmeldeverfahren (die Authenticator-Achse) genau das Niveau der Einladung.
- Ebenfalls verworfen:
  - **ein eigener Realm**: Das wäre ein zweiter Aussteller (Issuer), den jeder Fachdienst kennen
    müsste.
  - **ein eigener Client je Vorgang**: Das ergäbe ein anderes `azp` und widerspräche dem einen
    Browser-Client aus [ADR-42](ADR-042-loa1-anmeldung-umschalten.md).
  - **die Einschränkung als OAuth-Scope**: Den Scope bestimmt der Client, nicht der Anmeldeweg. Bei
    SSO in einen anderen Client fehlt er.
- **Transient Users:** Die Funktion ist als EXPERIMENTAL markiert und nur für das Identity Brokering
  gedacht. Verworfen.
- **Ein Link statt eines Kennworts (Action Token):** Das wäre möglich, weil der Einladungs-Nutzer
  existiert, ist für einen Brief aber unpraktisch. Für eine Einladung per E-Mail bleibt das der Weg.

## Folgen und offene Punkte

- Zunächst gibt es den Vorgangszugang nur im Web-Kanal. `auth-invite-lookup` ist im App-Kanal
  gesperrt, und die Bindung einer Einladung verlangt einen Web-Kanal (`ChannelType.WEB`). Für die
  App müsste der Grant aus
  [ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) ein
  Subjekt statt `account_id` annehmen. Ein Grant ist hier der Weg, auf dem die App ihre Tokens
  bekommt.
- Es bleibt ein Restrisiko wie beim Freischaltcode: Wer den Brief nach der rechtmäßigen Nutzung
  findet, kommt bis zur Frist oder bis zum Abschluss des Vorgangs wieder hinein.
- Wer ein Token sieht, kann wegen des Id-Formats das Kennwort dieser einen Einladung offline raten
  (rund 59 Bit). Das wird bewusst in Kauf genommen.
- Die Schwachstelle ist ein Fachdienst, der `process` nicht prüft. Die Regel sollte deshalb an
  zentraler Stelle geprüft werden, etwa in einem Gateway.
- Offen ist, ob `loa2` aus dem Brief allein genügt oder ob ein `loa2`-Vorgang zusätzlich eine SMS
  verlangt. Offen ist auch, ob die Einladung nach dem Vorgang als Identifizierung für eine
  Registrierung dienen soll.

**Nachtrag 2026-09-30 (vierte Bewertung, A-1/A-2).** Die Anfrage von Keycloak nennt das Subjekt
genauso wie die Antwort (`KeycloakChannelUpsertRequest.subject`). Gehört ein Kanal schon einem
anderen Subjekt, lehnt der Orchestrator die Anfrage mit `409` ab. So wird eine Einladung nie
unbemerkt zu einem Konto.

Keycloak meldet auch die Abmeldung eines Einladungs-Nutzers
(`POST …/kc/invitations/{id}/sign-outs`). Diese Meldung beendet die Web-Kanäle der Sitzung.

Das Anmeldeprotokoll (`account.sign_in_log`) führt Vorgangszugänge als Zeilen der Einladung statt
eines Kontos (`ck_sign_in_log_one_subject`). Diese Zeilen bleiben nur für die Aufbewahrungsfrist des
Protokolls erhalten. Es gibt kein Konto, mit dessen Löschung sie gelöscht werden könnten.

**Nachtrag 2026-09-30 (K-5): Ein Vorgangszugang wird nicht aufgewertet.** Verlangt ein späterer
Durchlauf in einer Einladungssitzung ein höheres Niveau, lehnt der Orchestrator den Kanal mit `409`
ab („Dieses Einmalkennwort genügt dem verlangten Sicherheitsniveau nicht“) und legt ihn nicht an. Die
Anmeldeseite zeigt diesen Grund an.

Eine Einladung bindet einen Kanal nur durch ihren eigenen Nachweis, nie durch das Subjekt, das
Keycloak nennt. Der Resume-Schritt, der sonst eine frühere Sitzung wiederherstellt, überspringt
Einladungssitzungen, denn dort gibt es nichts wiederherzustellen. Wer ein höheres Niveau braucht,
braucht eine Einladung mit diesem Niveau.
