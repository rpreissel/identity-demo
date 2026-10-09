# ADR-50: API-Versionierung auf zwei Ebenen – Umschlag und Tool

**Status:** umgesetzt 2026-10-04 (Issue `DPoP-demo-cszz`). Seit
[ADR-51](ADR-051-versionen-als-pfadsegment.md) sind die Pfade, die Fassungen der Tools und die
Dateien der Tool-Teile anders. Die Aufteilung in Umschlag, Tool und `/kc` gilt weiter.

**Entscheidung.** Die App spricht mit dem Server über eine HTTP-Schnittstelle. Ihr Vertrag steht in
`api/openapi.yaml`. Damit eine ausgelieferte App nicht plötzlich ausfällt, wird ein
veröffentlichter Stand des Vertrags „eingefroren“: Er wird als Datei abgelegt, und jede spätere
Änderung wird mit ihm verglichen. Findet der Vergleich eine Änderung, mit der ein alter Client nicht
mehr zurechtkommt, ist das ein **Bruch**.

Bisher wurde `api/openapi.yaml` als Ganzes eingefroren und geprüft. Jetzt wird die Datei in Teile
zerlegt, und jeder Teil wird für sich eingefroren und geprüft:

- **Umschlag** (`api/contract/envelope.yaml`): Das ist der Teil, der in jeder Antwort enthalten ist,
  egal welches Tool gerade läuft. Dazu gehören `/app/channels`, `/channels/{id}` ohne die Tool-Pfade,
  `tools/catalog`, `texts`, `ChannelResponse`, `ErrorResponse` und die gemeinsamen Formen von
  `StepData` (`missing-fields`, `confirm`, `message`, `failed-attempt`, `select-method`). Ein
  Bruch hier betrifft jeden Client. Er braucht deshalb eine neue API-Version (`/api/v2`).
- **Tool** (`api/contract/tools/<toolId>.yaml`): Ein [Tool](../glossar/glossar.md) ist ein
  abgeschlossener Arbeitsschritt wie „SMS einrichten“. Zu seinem Teil gehören die Pfade
  `channels/{id}/tools/<toolId>` und `tools/{id}/<toolId>…`, ihre Schemas und die
  `StepData`-Formen, die das Modul des Tools deklariert. Ein Bruch hier betrifft nur Clients, die
  das Tool in `availableTools` nennen. Man ändert das Tool dann additiv, zum Beispiel mit einem
  optionalen Feld oder einer neuen Form. Geht das nicht, braucht es eine Tool-Version (siehe
  Folgen).
- **Keycloak-intern** (`/kc/**`): Dieser Teil wird nicht eingefroren. Ein Endpunkt, den nur
  Keycloak aufruft, liegt immer unter `/kc/`. Das gilt auch dann, wenn er zu einem Tool-Modul
  gehört. Aus demselben
  Grund fehlt in den Teilen auch die zweite Anmeldeart über [Peer-Auth](../glossar/glossar.md), die
  gemeinsame Endpunkte für Keycloak anbieten. Peer-Auth ist die Art, wie sich Keycloak und
  Orchestrator gegenseitig ausweisen.

`OpenApiSnapshotTest` erzeugt die Teile im selben Lauf wie `api/openapi.yaml` (`ContractSplit`).
Welcher Pfad zu welchem Teil gehört, ergibt sich aus dem Code: Die Tool-Pfade folgen aus den
toolIds der `ToolModule`-Beans, die Formen aus dem Modul, das sie deklariert. `publishApiVersion`
friert die Teile unter `api/published/v1/envelope.yaml` und `api/published/tools/` ein.
`checkPublishedApiCompatibility` vergleicht jeden Teil mit seinem eingefrorenen Gegenstück. Ein
neues Tool ist kein Bruch. Ein entferntes Tool ist es auch nicht, denn Clients, die es nennen,
bekommen es nur nicht mehr angeboten. Beides meldet der Task als Hinweis.

**Warum.** Ein Client muss beim Anlegen eines Kanals (`POST /app/channels`) immer `availableTools`
mitschicken, also die Liste der Tools, die er darstellen kann. Ein Tool, das der Client dort nicht
nennt, wird ihm nie angeboten, und ein direkter Aufruf wird abgelehnt. Ein Bruch an einem Tool
betrifft also nur einen Teil der Clients. Der bisherige Vergleich über die ganze Datei konnte das
nicht unterscheiden. Deshalb wurde `api/published/v1.yaml` zwischen dem 28.09. und dem 04.10.2026
sechsmal neu eingefroren:

| Commit | Bruch | Ebene |
|---|---|---|
| f16110a | Keycloak-Upsert `accountId` → `subject` | `/kc`, nicht eingefroren |
| 9292f23 | Enum-Werte `KEYCLOAK` → `WEB`, Rollennamen | Umschlag, neue API-Version |
| 9292f23 | `partnernr` → `partnerNumber` in fsc, kvnr, invite | je Tool; additiv lösbar (altes Feld weiter annehmen) |
| 69e4b5d | Statuscodes berichtigt, `availableTools` Pflicht | Berichtigung (Erratum) am Umschlag |
| 4ae0be9 | `confirm-qr-login` → `approve-qr`, `auth-invite` → `auth-invite-lookup` | Ein Tool entfällt, ein neues kommt hinzu; kein Bruch |
| e90b007 | `tools/enroll-password/mgmt` entfernt | Keycloak, nicht eingefroren |
| 3338075 | Peer-Auth an `/app/channels` entfernt | Keycloak, nicht eingefroren |

Wäre die App schon veröffentlicht gewesen, hätte nur eine dieser Änderungen eine neue API-Version
erzwungen. Drei betrafen nur die Keycloak-Erweiterung. Sie wird zusammen mit dem Server gebaut und
ausgeliefert.

**Erwogene Alternative:** eine Version je Modul. Sie hätte den Bruch in 9292f23 nicht verhindert,
denn die Umbenennungen betrafen mehrere Module und das gemeinsame Antwortformat zugleich.

**Folgen.**

- In einer Tool-Datei stehen die Schemas des Umschlags nur als offene Platzhalter. So erscheint ein
  Bruch am Umschlag nur einmal und nicht in jeder Tool-Datei. Von `ChannelResponse` bleibt dort nur
  der Weg zu `StepData` stehen, weil openapi-diff nur vergleicht, was von den Pfaden aus erreichbar
  ist.
- Die Formen gehören dem Modul, nicht dem einzelnen Tool. Ändert sich `EnrollSmsStep`, meldet der
  Vergleich alle Tools von `auth_sms`. Das ist strenger als nötig, aber nie zu nachsichtig.
- openapi-diff hält es für kompatibel, wenn in einer Anfrage ein optionales Feld wegfällt. Eine
  Umbenennung wie `partnernr` → `partnerNumber` bemerkt der Vergleich daher nicht. Sie muss im
  Review auffallen.
- **Eine Tool-Version gibt es noch nicht.** Dafür gibt es drei Gründe: Die toolId ergibt sich aus
  Verfahren und Rolle (`ToolRole.toolIdFor`). Jede Rolle gibt es je Modul nur einmal. Und die toolId
  wird zugleich als Herkunft eines Werts gespeichert (`ClaimSource`). Ein zweites `auth-sms` neben
  dem ersten lässt das Modell deshalb nicht zu. Nötig wird eine Tool-Version erst, wenn ein Client
  getrennt vom Server ausgeliefert wird und sich ein Tool nicht additiv ändern lässt. Dann braucht
  es drei Dinge (Issue `DPoP-demo-7luc`):
  - eine Versionsangabe an der toolId,
  - eine `ClaimSource`, die sich aus Verfahren und Rolle ergibt,
  - eine Regel im Katalog, die die neuere Version anbietet, wenn ein Client zwei Versionen nennt.
- Bis zum ersten Release wird statt einer neuen Version neu eingefroren (`publishApiVersion`).
