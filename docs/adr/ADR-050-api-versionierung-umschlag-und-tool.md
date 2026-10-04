# ADR-50: API-Versionierung auf zwei Ebenen – Umschlag und Tool

**Status:** umgesetzt 2026-10-04 (Issue `DPoP-demo-cszz`). Pfade, Tool-Fassungen und Dateien der
Tool-Teile sind seit [ADR-51](ADR-051-versionen-als-pfadsegment.md) anders; die Aufteilung in
Umschlag, Tool und `/kc` gilt weiter.

**Entscheidung.** Eingefroren und auf Brüche geprüft wird nicht mehr `api/openapi.yaml` als Ganzes,
sondern ihre Teile:

- **Umschlag** (`api/contract/envelope.yaml`): `/app/channels`, `/channels/{id}` ohne die
  Tool-Pfade, `tools/catalog`, `texts`, `ChannelResponse`, `ErrorResponse` und die gemeinsamen
  Formen von `StepData` (`missing-fields`, `confirm`, `message`, `failed-attempt`,
  `select-method`). Ein Bruch hier trifft jeden Client und braucht eine neue API-Version
  (`/api/v2`).
- **Tool** (`api/contract/tools/<toolId>.yaml`): die Pfade `channels/{id}/tools/<toolId>` und
  `tools/{id}/<toolId>…`, ihre Schemas und die `StepData`-Formen, die das Modul des Tools
  deklariert. Ein Bruch hier trifft nur Clients, die das Tool in `availableTools` nennen. Man
  ändert das Tool dann additiv (optionales Feld, neue Form); geht das nicht, braucht es eine
  Tool-Version (siehe Folgen).
- **Keycloak-intern** (`/kc/**`): nicht eingefroren. Ein Endpunkt, den nur Keycloak aufruft, liegt
  immer unter `/kc/`, auch wenn er einem Tool-Modul gehört (`MgmtPasswordController`:
  `/kc/accounts/{accountId}/password-checks`). Die Peer-Auth-Alternative an gemeinsamen
  Endpunkten fällt aus den Teilen aus demselben Grund heraus.

`OpenApiSnapshotTest` erzeugt die Teile aus demselben Lauf wie `api/openapi.yaml` (`ContractSplit`);
die Zuordnung ergibt sich aus dem Code: Tool-Pfade aus den toolIds der `ToolModule`-Beans,
Formen aus dem Modul, das sie deklariert. `publishApiVersion` friert sie unter
`api/published/v1/envelope.yaml` und `api/published/tools/` ein; `checkPublishedApiCompatibility`
vergleicht jeden Teil mit seinem Gegenstück. Ein neues Tool ist kein Bruch, ein entferntes auch
nicht – Clients, die es nennen, bekommen es nur nicht mehr angeboten. Beides meldet der Task als
Hinweis.

**Warum.** `availableTools` ist bei `POST /app/channels` Pflicht. Ein Tool, das der Client nicht
nennt, wird ihm nie angeboten, und ein direkter Aufruf wird abgelehnt. Ein Bruch an einem Tool
trifft also nur einen Teil der Clients, nur der Vergleich wusste das nicht. `api/published/v1.yaml` wurde zwischen
dem 28.09. und dem 04.10.2026 sechsmal neu eingefroren:

| Commit | Bruch | Ebene |
|---|---|---|
| f16110a | Keycloak-Upsert `accountId` → `subject` | `/kc`, nicht eingefroren |
| 9292f23 | Enum-Werte `KEYCLOAK` → `WEB`, Rollennamen | Umschlag, neue API-Version |
| 9292f23 | `partnernr` → `partnerNumber` in fsc, kvnr, invite | je Tool; additiv lösbar (altes Feld weiter annehmen) |
| 69e4b5d | Statuscodes berichtigt, `availableTools` Pflicht | Erratum am Umschlag |
| 4ae0be9 | `confirm-qr-login` → `approve-qr`, `auth-invite` → `auth-invite-lookup` | Tool entfällt, neues kommt; kein Bruch |
| e90b007 | `tools/enroll-password/mgmt` entfernt | Keycloak, nicht eingefroren |
| 3338075 | Peer-Auth an `/app/channels` entfernt | Keycloak, nicht eingefroren |

Nach einem Release hätte nur einer davon eine neue API-Version erzwungen. Drei betrafen nur die
Keycloak-Erweiterung, die mit dem Server gebaut und ausgeliefert wird.

**Erwogene Alternative:** eine Version je Modul. Sie hätte den Bruch in 9292f23 nicht verhindert: Die
Umbenennungen gingen quer durch mehrere Module und das gemeinsame Antwortformat.

**Folgen.**

- In einer Tool-Datei stehen die Schemas des Umschlags nur als offene Platzhalter. Ein Bruch am
  Umschlag erscheint so einmal, nicht in jeder Tool-Datei. `ChannelResponse` behält dort nur den
  Weg zu `StepData`, weil openapi-diff nur vergleicht, was die Pfade erreichen.
- Formen gehören dem Modul, nicht dem einzelnen Tool. Ändert sich `EnrollSmsStep`, meldet der
  Vergleich alle Tools von `auth_sms`. Das ist strenger als nötig, aber nie zu lasch.
- openapi-diff hält ein entfallenes optionales Feld in einer Anfrage für kompatibel. Eine
  Umbenennung wie `partnernr` → `partnerNumber` fällt dem Vergleich daher nicht auf; sie bleibt
  eine Frage des Reviews.
- **Eine Tool-Version gibt es noch nicht.** Die toolId folgt aus Methode und Rolle
  (`ToolRole.toolIdFor`), je Modul gibt es jede Rolle einmal, und die toolId ist zugleich die
  gespeicherte `ClaimSource`. Ein zweites `auth-sms` neben dem ersten lässt das Modell nicht zu.
  Nötig wird das erst, wenn ein Client getrennt vom Server ausgeliefert wird und ein Tool sich nicht
  additiv ändern lässt; dann braucht es eine Versionsangabe an der toolId, eine `ClaimSource` aus
  Methode und Rolle und eine Katalogregel, die bei zwei genannten Versionen die neuere anbietet
  (Issue `DPoP-demo-7luc`).
- Bis zum ersten Release wird statt einer neuen Version neu eingefroren (`publishApiVersion`).
