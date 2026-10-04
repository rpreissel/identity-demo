# ADR-51: Orchestrator und Tools einzeln versioniert, beide per Pfadsegment

**Status:** umgesetzt 2026-10-04 (Issue `DPoP-demo-7luc`). Baut auf
[ADR-50](ADR-050-api-versionierung-umschlag-und-tool.md) auf und ersetzt dort die Aussage, dass es
keine Tool-Version gibt.

**Entscheidung.** Orchestrator und Tools haben getrennte Pfadräume. Jeder trägt seine Version als
Segment im Pfad, und jede Version zählt für sich.

| Ebene | Pfad | Version gilt für |
|---|---|---|
| Orchestrator | `/orchestrator/api/v1/…` | Umschlag: Kanäle, Katalog, Texte, `ChannelResponse` |
| Tool | `/tools/api/<toolId>/v<N>/…` | ein Tool: seine Pfade, DTOs und Schritt-Formen |

- **Ein Tool deklariert seine Fassungen.** Jede Deklaration im Modul nennt sie ausdrücklich
  (`SmsModule.enroll(ENROLL_SMS_TOOL_ID, versions = setOf(1), …)`), es gibt keinen Vorgabewert. Je
  Fassung gibt es einen Controller; sein Pfad nennt sie. Jedes Tool beginnt mit `v1`, einen Pfad
  ohne Fassung gibt es nicht.
- **Die toolId bleibt.** Herkunft (`ClaimSource`), `amr`, Kandidatenwahl, `next.toolId` und das
  Angebot rechnen weiter mit der toolId. Die Fassung gehört nur zum Vertrag mit dem Client
  (`ToolVersion`).
- **Der Client nennt seine Fassung.** Ein Client spricht je Tool genau eine Fassung und nennt sie in
  `availableTools` als `<toolId>@<version>` (`enroll-sms@1`). Ohne Fassung, unlesbar oder ein Tool
  zweimal: `400`. Ein Tool in einer Fassung, die der Server nicht führt, fällt weg wie ein
  unbekanntes und wird nie angeboten. Die Keycloak-Erweiterung meldet die Fassung ihrer
  Renderer (`WebToolRendererFactory.version()`), das Frontend die seiner Tool-Module.
- **Der Kanal hält die Fassung fest.** Ein Aufruf in einer anderen Fassung als der deklarierten
  wird wie ein nicht deklariertes Tool abgelehnt (`409`). Eine Fassung, die der Server nicht führt,
  hat keine Route (`404`).
- **Ein Tool startet in seinem eigenen Pfadraum:** `POST /tools/api/<toolId>/v<N>?channel=<id>`,
  `201` mit `Location: /tools/api/<toolId>/v<N>/<toolSessionId>`. Der Kanal steht in der Query,
  weil der Body dem Tool gehört; DPoP prüft den Pfad ohne Query (`htu`).
- **Verlassen und Zurück** (`DELETE …/<toolSessionId>`, `POST …/<toolSessionId>/back`) haben in
  jedem Tool und jeder Fassung dieselbe Form und gehören deshalb zum Umschlag.
- **Eine neue Orchestrator-Version ist ein Pflichtupdate.** Der Server führt genau eine; jedes Tool
  antwortet im Umschlag dieser Version. Der Kanal merkt sich keine Orchestrator-Version, und kein
  Tool gibt zwei Umschlag-Formen aus. Der Umschlag ändert sich deshalb möglichst nur additiv.
- **Vertragsdateien je Fassung:** `api/contract/tools/<toolId>/v<N>.yaml`, eingefroren unter
  `api/published/tools/<toolId>/v<N>.yaml`. `checkPublishedApiCompatibility` meldet einen Bruch an
  `<toolId>@<N>`; die Abhilfe ist eine additive Änderung oder eine neue Fassung.

**Warum.** Ein Client wird getrennt vom Server ausgeliefert, alte App-Versionen bleiben im Einsatz,
und jede beherrscht je Tool eine Fassung. Ein Tool muss sich deshalb brechend ändern können, ohne
die anderen Tools oder den Orchestrator mitzunehmen. Ein Mechanismus für beide Ebenen ist leichter
zu verstehen als zwei, und die erste Fassung ist kein Sonderfall.

**Erwogene Alternativen.**

- **Fassung in der toolId** (`enroll-sms-v2`). Zwei Mechanismen nebeneinander, die toolId auf der
  Leitung wiche von der gespeicherten Herkunft ab, ein Suffix müsste reserviert werden, und die
  erste Fassung hätte keines.
- **Orchestrator-Version auch im Tool-Pfad** (`/orchestrator/api/v1/tools/enroll-sms/v2/…`). Mit
  einer neuen Orchestrator-Version änderte sich jeder Tool-Pfad; die Ebenen wären gekoppelt.
- **Zwei Orchestrator-Versionen nebeneinander.** Der Kanal müsste sich seine Version merken, und
  jedes Tool müsste den Umschlag in beiden Formen ausgeben.
- **Eine Version für alles** (`/api/v2`). Alle Tools und der Umschlag müssten doppelt geführt
  werden, obwohl sich ein Tool ändert.

**Folgen.**

- Der neue Pfadraum `/tools/api` braucht einen eigenen Eintrag im Vite-Proxy. Compose und die
  OpenShift-Route leiten den ganzen Host weiter und brauchen nichts.
- Eine zweite Fassung eines Tools, das Abschalten einer Fassung und der Hinweis „App veraltet“
  sind noch nicht gebaut: [ideen/tool-versionen.md](../ideen/tool-versionen.md).
- Bis zum ersten Release wird statt einer neuen Fassung neu eingefroren (`publishApiVersion`), wie in
  ADR-50.
