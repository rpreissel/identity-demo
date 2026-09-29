# Idee: Vorgangszugang mit Einmalkennwort

Status: **Entwurf 2026-09-29** (Issue `DPoP-demo-uhj4`). Noch nicht entschieden, nichts umgesetzt.

Die Kasse will Personen zu einem bestimmten Vorgang einladen, auch solche, die kein Konto haben: alle
Kunden oder eine Teilmenge. Jede Person bekommt per Post ein Einmalkennwort. Damit meldet sie sich an
und erhält Access- und ID-Token, die aussehen wie die eines Kontos, aber einen Marker tragen: Sie
gelten nur für diesen einen Vorgang. Der Vorgang selbst ist ein gewöhnlicher, den Kontoinhaber auf
dem normalen Weg aufrufen. Das Kennwort hat eine Frist und bleibt bis zum Abschluss des Vorgangs
gültig; die Person kann morgen wiederkommen und sich noch einmal anmelden. Den Abschluss meldet das
Fachsystem, das den Vorgang führt.

Drei Vorgaben liegen dem Entwurf zugrunde: In Keycloak wird nur benutzt, was Keycloak selbst
vorsieht. Die Tokens unterscheiden sich von normalen nur durch den Marker. Und für die eingeladene
Person entsteht kein Konto. Zunächst geht es um den Web-Kanal; die App kann folgen (Abschnitt 7).

Die naheliegende Idee, die Einladungen in einem **eigenen User Storage** zu führen, wurde an den
Quellen von Keycloak 26.6.4 geprüft und **bestätigt**; die Gründe stehen in Abschnitt 2.

**Begriffe.** Ein *Vorgang* (`process`) ist der fachliche Prozess, für den eingeladen wird. Eine
*Einladung* (`invitation`) verbindet eine Person, einen Vorgang, ein Einmalkennwort, ein Niveau und
eine Frist. Der *Vorgangszugang* ist die Sitzung mit ihren Tokens, die aus einer Einladung entsteht.
„Einmal“ heißt „für einen Vorgang“, nicht „einmal nutzbar“, so wie beim Freischaltcode
([ADR-31](../adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md)).

---

## 1) Ausgangslage

Keycloak hält keine eigenen Nutzer. Die Konten des Orchestrators *sind* die Nutzer; die
Nutzer-Federation `OrchestratorStorageProvider` liest ein Konto bei Bedarf nach, und das `sub` jedes
Tokens ist `f:orch-accounts:<accountId>` ([ADR-38](../adr/ADR-038-keycloak-liest-konten.md)). Ein Konto
gilt als eingerichtet und anmeldefähig, sobald es ein Anmeldeverfahren hat (ADR-46). Daraus folgt
gleich die erste Grenze: Eine Einladung darf kein Anmeldeverfahren an einem Konto sein, sonst legt
eine Aussendung an alle Kunden Millionen anmeldefähiger Konten an.

Was heute im Token steht, kommt aus zwei Quellen. `acr` und `amr` schreibt der Orchestrator als
User-Session-Notes, die `OrchestratorAcrAmrMapper` ins Token hebt. Die Stammdaten (`person_id`,
`kvnr`, `versnr`, Name, Adresse) sind Attribute des föderierten Nutzers, die Keycloaks eingebaute
Attribut-Mapper im Scope `orchestrator-claims` übernehmen. Einen Vorgangsbegriff gibt es nirgends:
weder am Kanal noch an der Journey noch im Token.

Ein Vorbild gibt es trotzdem: den Freischaltcode. Er wird per Brief verschickt, liegt nur als Hash
vor, gilt bis zu seinem Ablauf und wird durch Nutzung nicht verbraucht (ADR-31). `ident-fsc` fragt
ihn zusammen mit Versicherungs- oder Partnernummer ab und löst die Person über den Port
`PersonDirectory` auf. Der Entwurf übernimmt dieses Muster für die Anmeldung.

## 2) Was geprüft wurde, und was der eigene User Storage kann

**Die Einladung als Verfahren an einem Konto.** Verworfen. Es bräuchte je eingeladener Person ein
Konto, und mit dem ersten Verfahren wäre es eingerichtet (ADR-46): anmeldefähig, mit Postfach, mit
Weg zu `MANAGE_AUTH_METHODS`. Hat die Person schon ein Konto, träfe der Brief auf dasselbe `sub` wie
ihr echtes Konto. Vergisst dann ein Fachdienst die Prüfung des Markers, hat der Briefbesitzer das
Konto. Der Marker wäre die einzige Trennung, und Trennungen, die aus einer einzigen Prüfung bestehen,
halten nicht.

**Ein eigener Realm.** Verworfen. Clients, Theme, Flows und Migrationen doppelt; jeder Fachdienst
müsste zwei Issuer kennen und das Token trüge einen anderen `iss`.

**Ein eigener Client je Vorgang mit Flow-Override.** Verworfen. Das Token trüge ein anderes `azp`,
der Web-Kanal hat absichtlich genau einen Browser-Client (ADR-42), und der Vorgang wird von
Kontoinhabern über diesen Client aufgerufen. Der Eingang muss derselbe sein.

**Die Einschränkung als OAuth-Scope.** Nicht als Träger gewählt. Welche Scopes ein Token hat,
bestimmt der Client in seiner Anfrage, nicht der Anmeldeweg. Der Marker muss aber aus der Anmeldung
kommen, und er muss auch dann im Token stehen, wenn ein anderer Client sich die Sitzung per SSO
teilt.

**Transient Users.** Verworfen. Sie sind EXPERIMENTAL und nur für das Identity Brokering gedacht
(siehe [ident-nect.md](ident-nect.md), Abschnitt 2).

**Ein zweiter User Storage `orch-invitations`.** Gewählt. Die Einladung ist der Nutzer. Was das in
Keycloak 26.6.4 bedeutet, wurde an den Quellen geprüft:

- `StorageId.getId()` bildet die Nutzer-Id als `f:<componentId>:<externalId>`. Ein zweiter
  Provider mit fester Komponenten-Id liefert einen eigenen `sub`-Namensraum
  `f:orch-invitations:…`, der sich nie mit einem Konto überschneidet, auch nicht mit dem Konto
  derselben Person.
- Sitzungen, SSO, Refresh, Logout, Brute-Force-Schutz und Consent arbeiten für einen föderierten
  Nutzer wie für jeden anderen. Nichts muss nachgebaut werden.
- Beim Refresh prüft `TokenManager`, dass der Nutzer noch existiert und aktiv ist, und antwortet
  sonst mit `invalid_grant` („User disabled“). Meldet der Provider eine abgeschlossene Einladung als
  deaktiviert, endet die Sitzung von selbst spätestens mit dem nächsten Token.
- Keycloaks Attribut-Mapper (`oidc-usermodel-attribute-mapper`) lassen einen Claim weg, wenn das
  Attribut fehlt. Der Marker kann also ein Nutzerattribut sein: Einladungs-Nutzer tragen es, Konten
  nicht, und derselbe Scope `orchestrator-claims` bedient beide.
- Ein Provider ohne Suche nach Benutzername oder E-Mail und ohne Credential-Typ ist erlaubt. Die
  native Passwortmaske und jede Suche finden einen Einladungs-Nutzer dann nie.

**Ein Link statt eines Kennworts (Action Token).** Für den Brief nicht gewählt, aber möglich:
Anders als bei Nect gäbe es hier einen Nutzer, den ein Action Token nennen kann. Eine lange URL
abzutippen ist unpraktischer als ein Kennwort; für eine spätere Einladung per E-Mail wäre es der
natürliche Weg (Abschnitt 7).

## 3) Der Entwurf

1. **Einladen.** Das Fachsystem ruft die System-API des Orchestrators auf: Vorgang, Niveau (`loa1`
   oder `loa2`), Frist und die Liste der Personen (`personId`; alle Kunden oder eine Teilmenge). Der
   Orchestrator erzeugt je Person ein Einmalkennwort mit zwölf Zeichen aus dem Alphabet der
   Freischaltcodes (rund 60 Bit), speichert nur seinen SHA-256-Hash und gibt den Klartext genau
   einmal zurück. Das Fachsystem druckt die Briefe und verwahrt Klartext oder Hash. **Der Hash ist
   die Identität der Einladung:** Er ist der Schlüssel im Orchestrator, der externe Teil des `sub`,
   der Claim im Token und der Name, unter dem das Fachsystem die Einladung beendet. Der Hash ist
   deterministisch und ohne Pepper, damit das Fachsystem ihn aus dem Klartext selbst bilden kann; die
   Entropie des Kennworts ersetzt den Pepper (Abschnitt 6).
2. **Anmelden.** Der Brief nennt die Adresse des Vorgangs und das Kennwort. Die Vorgangsseite startet
   die normale OIDC-Anmeldung über den Browser-Client, mit dem `acr_values`, das der Vorgang verlangt.
   In der Verfahrensauswahl des Orchestrators erscheint ein neues Tool `auth-invite` („Mit
   Einmalkennwort anmelden“). Es ist ein Anmeldeverfahren ohne bekanntes Konto (`LOOKUP_AUTH`, wie
   `auth-qr-lookup`), Faktor Besitz, `maxAcr = loa2`, nicht `demoOnly`: Frist und Abschluss sagt der
   Orchestrator selbst zu, den Postweg das Fachsystem. Das Formular fragt Versicherungs- **oder**
   Partnernummer und das Einmalkennwort, mit denselben Feldern wie `ident-fsc`.
3. **Prüfen.** Das Tool löst die Nummer über `PersonDirectory` zur `personId` auf und fragt den Port
   `Invitations`: Gibt es zu Hash *und* Person eine Einladung, die weder abgelaufen noch
   abgeschlossen noch widerrufen ist? Der Hash allein genügt nie. Fehlversuche zählt die bestehende
   Drossel je Person (`PersonLockoutService`, fünf in fünfzehn Minuten). Bei Erfolg endet das Tool mit
   `Completed.ProcessAccess(hash, acr der Einladung)`.
4. **Abschließen der Anmeldung.** Die Journey endet `AUTHENTICATED` mit einem **Subjekt, das kein
   Konto ist.** Die Antwort an Keycloak nennt statt `accountId` das Subjekt `invitation:<hash>`,
   dazu `acr` gleich dem Niveau der Einladung und `amr = ["invite"]`. Verlangt der Subflow `loa2` und
   die Einladung trägt nur `loa1`, lehnt `LoginCompletion.judge` ab wie heute bei jedem `acr` unter
   `targetAcr`. Der Authenticator setzt den Nutzer `f:orch-invitations:<hash>`; Keycloak fragt den
   zweiten Provider, der `GET …/kc/invitations/{hash}` beim Orchestrator aufruft.
5. **Der Nutzer.** Er trägt dieselben Stammdaten-Attribute wie ein Konto derselben Person (Name,
   `personId`, `kvnr`, `versnr`, Adresse, aus dem Personenverzeichnis über dieselbe Abbildung wie in
   `KcAccountViews`) und zwei weitere: `orchestratorInvitation` (der Hash) und `orchestratorProcess`.
   Er hat keinen Benutzernamen, den man suchen könnte, keine E-Mail, kein Credential; `enabled` ist er
   genau dann, wenn die Einladung noch gilt.
6. **Das Token.** Es entsteht aus denselben Mappern wie jedes andere. Zusätzlich stehen darin
   `process` und `invitation` (zwei weitere Attribut-Mapper im Scope `orchestrator-claims`), `amr`
   enthält `invite`, `acr` ist `loa1` oder `loa2`, `orchestrator_account_id` fehlt, und `sub` beginnt
   mit `f:orch-invitations:`. Die Regel für jeden Fachdienst lautet in einem Satz: **Trägt das Token
   `process`, gilt es nur für diesen Vorgang.** Das ist dieselbe Vertrauensstellung wie bei `acr`.
   Weil der Marker am Nutzer hängt, trägt ihn jedes Token dieser Sitzung, auch eines, das sich ein
   anderer Client per SSO holt; der Vorgangszugang lässt sich nicht durch einen Wechsel der
   Anwendung verlassen.
7. **Wiederkommen.** Bis zur Frist oder zum Abschluss sind beliebig viele Anmeldungen erlaubt, wie
   beim Freischaltcode. Jede ist eine eigene Keycloak-Sitzung, und der Web-Kanal lebt genau so lange
   wie sie (ADR-43).
8. **Beenden.** Das Fachsystem meldet den Abschluss aktiv: `POST …/invitations/{hash}/completion`,
   mit dem Hash aus dem Token oder aus dem eigenen Bestand. Der Orchestrator setzt `completedAt`. Ab
   jetzt meldet der Provider den Nutzer als deaktiviert, der nächste Refresh scheitert, und laufende
   Sitzungen beendet der Orchestrator zusätzlich per Admin-Logout des Nutzers über den bestehenden
   Client `orchestrator-admin`, Best-Effort wie bei `AccountRemoval`. Ein Widerruf durch die Kasse
   läuft denselben Weg; der Ablauf der Frist wirkt von selbst. Was Keycloak in seinem föderierten
   Speicher zu einer erledigten Einladung hält, räumt ein Job ab, analog zu `AccountRemoval`.

Was der Entwurf absichtlich nicht hat: Ein Einladungs-Subjekt hat keine Verfahren, also gibt es
keinen Step-up über das Niveau der Einladung hinaus, keine Verwaltung von Verfahren, keine
Registrierung aus der Sitzung und keine Verknüpfung mit einem Konto. Der Vorgang bekommt, was die
Einladung trägt, und nicht mehr.

**Zwei Anmeldungen im selben Browser.** Eine Keycloak-Sitzung gehört genau einem Nutzer, und der
Einladungs-Nutzer ist ein anderer als das Konto derselben Person. Daraus folgt für beide Richtungen,
ohne dass der Entwurf etwas tun müsste (geprüft in `AuthenticationProcessor`, Keycloak 26.6.4):

- **Vorgangszugang aktiv, dann ein weiterer Auth-Request** (gleicher oder anderer Client, auch
  still per SSO): `auth-cookie` meldet den Einladungs-Nutzer an, und jedes Token trägt den Marker.
  Ein anderer Fachdienst lehnt es ab, das Portal sieht am Claim `process`, dass hier kein Konto
  angemeldet ist, und bietet nur „Abmelden“ an. Erzwingt eine Anwendung eine neue Anmeldung
  (`prompt=login`) und die Person meldet sich dort mit ihrem Konto an, endet Keycloak mit seiner
  Fehlerseite „bereits als anderer Nutzer angemeldet“ (`DIFFERENT_USER_AUTHENTICATED`); die
  bestehende Sitzung bleibt. Ein Step-up auf ein Niveau über dem der Einladung scheitert, weil das
  Subjekt keine Verfahren hat.
- **Konto aktiv, dann der Brief:** Die Vorgangsseite bekommt still per SSO ein normales Konto-Token
  ohne Marker, und der Vorgang läuft uneingeschränkt; das Einmalkennwort wird nicht gebraucht. Wählt
  die Person trotzdem „Mit Einmalkennwort anmelden“ (nur über `prompt=login` erreichbar), ist das
  wieder ein anderer Nutzer, und Keycloak zeigt dieselbe Fehlerseite.

Der Weg von der einen in die andere Anmeldung führt in beiden Richtungen über die Abmeldung. Die
Vorgangsseite bietet „Mit Einmalkennwort anmelden“ deshalb nur an, wenn niemand angemeldet ist, und
sonst „Abmelden und mit Einmalkennwort anmelden“ (Keycloak-Logout mit Rücksprung auf die Seite).
Zwei Browser sind zwei Sitzungen und stören sich nicht.

## 4) Was im Orchestrator dazukommt

- **Modul `invitations`** mit eigenem Schema (ADR-16): `invitation(code_hash, process_id, person_id,
  acr, valid_until, completed_at, revoked_at, created_at)`, Schlüssel ist der Hash. Es stellt aus,
  prüft, schließt ab und widerruft. Nach außen bietet es den Port `tool_api.Invitations`
  (`verify(personId, code)`), analog zu `ActivationCodes`; das Tool-Modul `auth_invite` hängt nur an
  `tool_api` und nutzt daneben `PersonDirectory`, wie `ident_fsc` (Muster aus dem Nachtrag zu ADR-31).
- **Ein Subjekt neben dem Konto.** `ChannelSession` und `AuthJourney` kennen heute nur `accountId`.
  Sie bekommen ein Subjekt (`ACCOUNT` oder `INVITATION` mit Id); `accountId` bleibt für Konten, wie es
  ist. Die Antwort an den Keycloak-Kanal (`authData`) nennt das Subjekt statt nur der Konto-Id. Die
  Policy rechnet mit einem einzigen Nachweis (`MethodEvidence(method = "invite", loa = Niveau der
  Einladung, Faktor Besitz)`); es gibt nichts zusammenzufassen.
- **Eine System-API für das Fachsystem:** Einladungen anlegen (liefert die Klartexte), abschließen,
  widerrufen, Status lesen. Der Aufrufer weist sich wie `orchestrator-app-token` mit einer Signatur
  aus (ADR-9, ADR-25), mit einem eigenen Client ohne Admin-Rechte.
- **Ein Endpunkt für Keycloak:** `GET …/kc/invitations/{hash}` liefert den Einladungs-Nutzer mit
  Stammdaten und Marker, gesichert wie `KcAccountLookupController`.
- **Demo: Einmalkennwörter im Personenverzeichnis.** Die Rolle des Fachsystems übernimmt in der
  Demo die Oberfläche des Personenverzeichnisses (`/personenverzeichnis/`, `@DemoSurface`), dort wo
  heute die Freischaltcodes ausgestellt werden. An einer Person lässt sich ein Einmalkennwort
  anlegen, mit Vorgang (Auswahl aus wenigen Demo-Vorgängen), Niveau und Frist; eine Massenanlage
  braucht die Demo nicht. Die Oberfläche ruft die System-API des Orchestrators auf, wie es das echte
  Fachsystem täte, und legt einen Brief in den Briefkasten (`personenverzeichnis.brief`), mit
  Klartext wie beim Freischaltcode (ADR-22). Am Brief steht „Widerrufen“.
- **Demo: die Vorgangsseite nach der Anmeldung.** Wer sich mit einem Einmalkennwort anmeldet, landet
  auf einer Demo-Seite der Webseite, die den Vorgang spielt. Sie zeigt, wer angemeldet ist (Name,
  Versicherungsnummer aus den Claims) und für welchen Vorgang (Claim `process`), und bietet zwei
  Aktionen: **„Vorgang beenden“** reicht den Hash aus dem Claim `invitation` beim Orchestrator ein,
  stellvertretend für das Fachsystem (Demo-Endpunkt, `@DemoSurface`), und zeigt danach, dass die
  Sitzung endet; **„Abmelden“** ist der normale Keycloak-Logout. Aufklappbar darunter die technischen
  Details: ID- und Access-Token mit `sub`, `acr`, `amr`, `process`, `invitation`, Ablauf und dem
  Hinweis, welche Claims fehlen (`orchestrator_account_id`). Ruft ein Konto-Nutzer dieselbe Seite
  auf, zeigt sie, dass kein Marker im Token steht und der Vorgang uneingeschränkt gilt, und bietet
  „Abmelden und mit Einmalkennwort anmelden“ (Abschnitt 3, „Zwei Anmeldungen im selben Browser“).

## 5) Was in Keycloak dazukommt

- Ein zweiter `UserStorageProviderFactory` (`orchestrator-invitations`) mit fester Komponenten-Id
  `orch-invitations`, angelegt durch eine Migration wie `orch-accounts` in V2. Er kann nur
  `getUserById`; Suchen liefern nichts, Credentials gibt es keine, Cache 60 Sekunden wie beim
  Konto-Provider.
- Zwei Attribut-Mapper im Scope `orchestrator-claims` (`orchestratorInvitation → invitation`,
  `orchestratorProcess → process`) und die beiden Attribute im User Profile als admin-only, wie
  `orchestratorAccountId`.
- `OrchestratorAuthenticator` und `LoginCompletion` setzen und prüfen ein Subjekt statt nur einer
  Konto-Id.
- Ein `WebToolRenderer` für `auth-invite` mit dem Formular, in FreeMarker und Keycloakify.
- Nicht nötig: ein Realm, ein Client, ein Flow, ein Mapper mit eigenem Code, ein
  `RealmResourceProvider`.

## 6) Sicherheit

- **Kennwort.** Zwölf Zeichen aus 31 möglichen ergeben rund 60 Bit. Bei fünf Versuchen je Person in
  fünfzehn Minuten ist Raten aussichtslos, und ein aus der Datenbank gelesener Hash lässt sich nicht
  zum Kennwort zurückrechnen. Deshalb kann der Hash ohne Pepper bleiben; nur so kann das Fachsystem
  ihn zum Beenden selbst bilden. Der Freischaltcode mit acht Zeichen käme dafür nicht in Frage.
- **Zwei Angaben.** Ein Hash allein meldet nie an; er muss zur genannten Person passen. Wer ein
  Kennwort kennt, braucht auch die Versicherungs- oder Partnernummer des Empfängers.
- **Restrisiko wie beim Freischaltcode** (ADR-31): Wer den Brief nach der legitimen Nutzung findet,
  kann sich bis zur Frist oder zum Abschluss erneut anmelden. Begrenzt durch Frist, Abschluss und
  Widerruf.
- **Trennung.** Eigener `sub`-Namensraum, kein `orchestrator_account_id`, kein Credential, keine
  Suche, keine Verfahren. Ein Einladungs-Nutzer kann in Keycloak nichts anderes tun, als sich mit
  seinem Kennwort für seinen Vorgang anzumelden.
- **Die Schwachstelle** ist ein Fachdienst, der `process` nicht prüft. Die Regel aus Abschnitt 3
  gehört an eine zentrale Stelle, etwa ein Gateway, damit sie nicht jeder Dienst einzeln umsetzen
  muss.

## 7) Offene Fragen

1. **`loa2` aus dem Brief allein.** `ident-fsc` vergibt für den Briefbesitz `loa2` als
   Identifizierung; hier trüge er die Anmeldung selbst. Trägt das die Kasse, oder verlangt ein
   `loa2`-Vorgang zusätzlich eine SMS an die hinterlegte Nummer (`auth-sms` als zweites Tool in
   derselben Journey)?
2. **Wer erzeugt die Kennwörter?** Vorgeschlagen ist der Orchestrator, der den Klartext einmal
   liefert. Die Alternative: Das Fachsystem erzeugt sie und registriert nur Hashes; dann muss der
   Orchestrator die Stärke nicht garantieren, aber auch nicht prüfen können.
3. **Person mit Konto.** Vorgeschlagen: Die Einladung gilt trotzdem, der Vorgang sieht am Marker,
   welcher Weg es war. Oder soll die Anmeldeseite auf das Konto verweisen?
4. **Brücke zum Konto.** Der Brief ist zugleich ein Identitätsnachweis. Soll die Einladung nach dem
   Vorgang eine Registrierung als Identifizierung tragen (`ident-invite`, `loa2` wie `ident-fsc`)?
5. **Nach dem Abschluss.** Reicht es, dass Tokens bis zu ihrem Ablauf (höchstens fünf Minuten)
   weiterlaufen und der Refresh scheitert, oder ist der Admin-Logout Pflicht?
6. **App-Kanal.** Der Grant `urn:identity-demo:account-token` nähme ein Subjekt statt `account_id`,
   `AppLoginSession` kennte das Einladungs-Subjekt; sonst nichts Neues. Wann?
7. **E-Mail-Einladung.** Ein Action Token als Link, weil der Einladungs-Nutzer existiert. Lohnt ein
   zweiter Zustellweg?

## 8) Nächste Schritte (falls die Umsetzung gewünscht ist)

1. Modul `invitations` mit Port, Tool `auth_invite`, Subjekt in Kanal und Journey, Policy.
2. Zweiter Storage-Provider mit Migration, Attribut-Mapper, Subjekt in Authenticator und
   `LoginCompletion`, Renderer in beiden Themes.
3. System-API und Keycloak-Endpunkt; Einmalkennwort je Person und Briefe im Personenverzeichnis,
   Vorgangsseite mit „Vorgang beenden“, Abmelden und Token-Details auf der Webseite.
4. Ein Playwright-Test: Brief lesen, anmelden, Vorgang aufrufen, Fachsystem schließt ab, Refresh
   scheitert.
5. Danach eine ADR mit den Befunden aus Abschnitt 2 und die Kapitel 03, 05 und 07.
