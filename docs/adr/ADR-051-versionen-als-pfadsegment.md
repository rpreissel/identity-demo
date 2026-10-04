# ADR-51: Versionierung – Orchestrator und Tools einzeln, beide per Pfadsegment

**Status:** umgesetzt 2026-10-04 (Issues `DPoP-demo-7luc`, `DPoP-demo-wnd0`). Baut auf
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
- **Das Audit nennt die Fassung.** Wo ein Protokoll ein Tool nennt, steht es in derselben
  Schreibweise wie in `availableTools`: `change_log` bei `IDENTIFIED` und `METHOD_ADDED`
  (`tool`), `sign_in_log` bei `SIGNED_IN` und `STEPPED_UP` (`tools`) und bei `SIGN_IN_FAILED`
  (`tool`), `journey_trace` bei Aktivierung, Fehlversuch, Zurück, Abschluss und Abbruch eines Tools
  (die Trace-Ansicht zeigt es in der Spalte „Tool“). `detailsVersion` der betroffenen Ereignisse
  in `change_log` und `sign_in_log` ist dafür auf 2 gestiegen (ADR-39). Die Herkunft eines Werts (`claim_source`) bleibt
  die reine toolId: Sie ist fachlich das Tool, und ihre Vergleiche rechnen damit.
- **Der Betreiber sperrt je Fassung.** Die Sperre aus ADR-32 gilt für eine Fassung und einen
  Kanaltyp (`PUT /orchestrator/admin/tools/enroll-sms@1/availability/APP`); die Reihenfolge bleibt je
  Tool. So lässt sich eine alte Fassung erst abschalten und beobachten, bevor sie ausgebaut wird.
  Eine Voreinstellung ohne Fassung (`demo.tool-defaults`: `auth-qr`) sperrt alle Fassungen.
- **Vertragsdateien je Fassung:** `api/contract/tools/<toolId>/v<N>.yaml`, eingefroren unter
  `api/published/tools/<toolId>/v<N>.yaml`. `checkPublishedApiCompatibility` meldet einen Bruch an
  `<toolId>@<N>`; die Abhilfe ist eine additive Änderung oder eine neue Fassung.

**Wann ein Tool eine neue Fassung bekommt.** Nur wenn der Server wissen muss, was der Client kann:
bei einem neuen Pflichtfeld, einem unverzichtbaren neuen Aufruf oder Schritt, einer neuen
Schritt-Form oder einem umbenannten Feld. Eine additive Änderung ist immer der erste Weg. Die
vollständige Regel steht in [05-api.md](../05-api.md) Abschnitt 1.

**Was die alte Fassung ohne das Neue tut,** wird mit jeder neuen Fassung fachlich entschieden. Es
gibt drei Antworten: Sie setzt einen **Ersatzwert** und läuft weiter, sie liefert ein **geringeres
Ergebnis** (ein niedrigeres Niveau, eine Angabe weniger), oder sie **darf nicht mehr laufen** und wird
abgeschaltet. Die Antwort steht im Abschnitt des Verfahrens in [06-ablaeufe.md](../06-ablaeufe.md).

**Eine neue Fassung bauen.** Ein Handler bedient alle Fassungen und verzweigt nach
`ToolContext.version` nur, wo sich das Verhalten unterscheidet; jede solche Stelle ist mit
„entfällt mit v1“ markiert. Je Fassung gibt es einen Controller im Paket `api.v<N>` des Moduls mit
eigenen DTOs, der alte bleibt unverändert. Ein neues Feld in `missingFields` braucht keine neue
Schritt-Form; eine geänderte Form bekommt eine neue `kind`. Ausgerollt wird erst der Server mit
beiden Fassungen, dann der Client mit der neuen; danach kann der Server nicht mehr hinter diese
Fassung zurück. Schritt für Schritt: [15-beispiel-neues-verfahren.md](../15-beispiel-neues-verfahren.md)
Abschnitt 9.

**Beispiel `enroll-sms@2`.** Fassung 2 verlangt mit der Telefonnummer die Einwilligung (`consent`),
dass die Nummer gespeichert und für SMS-Codes genutzt wird. Fassung 1 setzt den Ersatzwert „keine
Einwilligung“ und läuft weiter, damit eine App, die die Checkbox nicht zeigen kann, weiter Nummern
einrichten kann. Die Einwilligung wird nicht eigens gespeichert: `METHOD_ADDED` im Änderungsprotokoll
nennt die Fassung, und daraus folgt, ob sie vorlag. Die App spricht Fassung 1, der Web-Kanal
Fassung 2 ([06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 4).

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
- **Noch nicht gebaut:** der Hinweis „App veraltet“ an Clients mit einer gesperrten oder
  ausgebauten Fassung (`DPoP-demo-kkod`) und die feste Antwort eines abgelösten Orchestrator-Pfads
  (`DPoP-demo-gd85`).
- Bis zum ersten Release wird statt einer neuen Fassung neu eingefroren (`publishApiVersion`), wie in
  ADR-50.
