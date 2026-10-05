# ADR-51: Versionierung – Orchestrator und Tools einzeln, beide per Pfadsegment

**Status:** umgesetzt 2026-10-04 (Issues `DPoP-demo-7luc`, `DPoP-demo-wnd0`). Baut auf
[ADR-50](ADR-050-api-versionierung-umschlag-und-tool.md) auf. Die Aussage dort, dass es keine
Tool-Version gibt, gilt nicht mehr.

**Entscheidung.** Die Schnittstelle des Servers hat zwei Ebenen. Der
[Orchestrator](../glossar/glossar.md) steuert die Abläufe. Zu ihm gehört der **Umschlag**: der Teil
jeder Antwort, der für alle Tools gleich ist, etwa die Kanäle, der Katalog, die Texte und
`ChannelResponse`. Die [Tools](../glossar/glossar.md) sind die einzelnen Arbeitsschritte, etwa „SMS
einrichten“. Jedes Tool hat eigene Pfade, DTOs und Schritt-Formen.

Orchestrator und Tools bekommen getrennte Pfadräume. In jedem Pfadraum steht die Version als
Segment im Pfad, zum Beispiel `v1`. Jede Version zählt unabhängig von den anderen.

| Ebene | Pfad | Version gilt für |
|---|---|---|
| Orchestrator | `/orchestrator/api/v1/…` | Umschlag: Kanäle, Katalog, Texte, `ChannelResponse` |
| Tool | `/tools/api/<toolId>/v<N>/…` | ein Tool: seine Pfade, DTOs und Schritt-Formen |

Eine Version eines Tools heißt im Folgenden **Fassung**. Daraus ergeben sich diese Regeln:

- **Ein Tool deklariert seine Fassungen.** Jede Deklaration im Modul nennt sie ausdrücklich
  (`SmsModule.enroll(ENROLL_SMS_TOOL_ID, versions = setOf(1), …)`). Einen Vorgabewert gibt es
  nicht. Für jede Fassung gibt es einen eigenen Controller, und sein Pfad nennt die Fassung. Jedes
  Tool beginnt mit `v1`. Einen Pfad ohne Fassung gibt es nicht.
- **Die toolId bleibt, wie sie ist.** Mit der toolId rechnen weiterhin:
  - die Herkunft eines Werts (`ClaimSource`),
  - die Liste der Anmeldeverfahren (`amr`),
  - die Kandidatenwahl,
  - `next.toolId`,
  - das Angebot.

  Die Fassung gehört nur zum Vertrag mit dem Client (`ToolVersion`).
- **Der Client nennt seine Fassung.** Ein Client spricht je Tool genau eine Fassung. Er nennt sie in
  `availableTools`, der Liste der Tools, die er darstellen kann, in der Form `<toolId>@<version>`
  (`enroll-sms@1`). Fehlt die Fassung, ist der Eintrag unlesbar oder steht ein Tool zweimal in der
  Liste, antwortet der Server mit `400`. Nennt der Client ein Tool in einer Fassung, die der Server
  nicht führt, behandelt der Server es wie ein unbekanntes Tool und bietet es nie an. Die
  Keycloak-Erweiterung meldet die Fassung ihrer Renderer (`WebToolRendererFactory.version()`), das
  Frontend die Fassung seiner Tool-Module.
- **Der Kanal merkt sich die Fassung.** Kommt ein Aufruf in einer anderen Fassung als der
  deklarierten, lehnt der Server ihn ab wie ein nicht deklariertes Tool (`409`). Für eine Fassung,
  die der Server nicht führt, gibt es keine Route (`404`).
- **Ein Tool startet in seinem eigenen Pfadraum:** `POST /tools/api/<toolId>/v<N>?channel=<id>`.
  Die Antwort ist `201` mit `Location: /tools/api/<toolId>/v<N>/<toolSessionId>`. Der Kanal steht
  in der Query, weil der Body dem Tool gehört. [DPoP](../glossar/glossar.md) prüft den Pfad ohne
  Query (`htu`).
- **Verlassen und Zurück** (`DELETE …/<toolSessionId>`, `POST …/<toolSessionId>/back`) haben in
  jedem Tool und jeder Fassung dieselbe Form. Deshalb gehören sie zum Umschlag.
- **Eine neue Orchestrator-Version ist ein Pflichtupdate.** Der Server führt genau eine
  Orchestrator-Version. Jedes Tool antwortet im Umschlag dieser Version. Der Kanal merkt sich keine
  Orchestrator-Version, und kein Tool gibt zwei Formen des Umschlags aus. Der Umschlag ändert sich
  deshalb möglichst nur additiv.
- **Das Audit nennt die Fassung.** Wo ein Protokoll ein Tool nennt, steht es in derselben
  Schreibweise wie in `availableTools`. Das betrifft:
  - `change_log` bei `IDENTIFIED` und `METHOD_ADDED` (`tool`),
  - `sign_in_log` bei `SIGNED_IN` und `STEPPED_UP` (`tools`) und bei `SIGN_IN_FAILED` (`tool`),
  - `journey_trace` bei Aktivierung, Fehlversuch, Zurück, Abschluss und Abbruch eines Tools. Die
    Trace-Ansicht zeigt es in der Spalte „Tool“.

  Dafür ist `detailsVersion` der betroffenen Ereignisse in `change_log` und `sign_in_log` auf 2
  gestiegen (ADR-39). Die Herkunft eines Werts (`claim_source`) bleibt die reine toolId. Fachlich
  ist sie das Tool, und die Vergleiche mit ihr rechnen damit.
- **Der Betreiber sperrt je Fassung.** Die Sperre aus ADR-32 gilt für eine Fassung und einen
  Kanaltyp (`PUT /orchestrator/admin/tools/enroll-sms@1/availability/APP`). Die Reihenfolge bleibt
  je Tool. So lässt sich eine alte Fassung erst abschalten und beobachten, bevor man sie ausbaut.
  Eine Voreinstellung ohne Fassung (`demo.tool-defaults`: `auth-qr`) sperrt alle Fassungen.
- **Vertragsdateien je Fassung:** `api/contract/tools/<toolId>/v<N>.yaml`, eingefroren unter
  `api/published/tools/<toolId>/v<N>.yaml`. „Eingefroren“ heißt: als veröffentlichter Stand
  abgelegt, mit dem jede spätere Änderung verglichen wird. `checkPublishedApiCompatibility` meldet
  einen Bruch an `<toolId>@<N>`. Abhilfe schafft eine additive Änderung oder eine neue Fassung.

**Wann ein Tool eine neue Fassung bekommt.** Nur dann, wenn der Server wissen muss, was der Client
kann. Das ist der Fall bei einem neuen Pflichtfeld, bei einem neuen Aufruf oder Schritt, auf den man
nicht verzichten kann, bei einer neuen Schritt-Form oder bei einem umbenannten Feld. Der erste Weg
ist immer eine additive Änderung. Die vollständige Regel steht in [05-api.md](../05-api.md)
Abschnitt 2, „Fassungen eines Tools“.

**Was die alte Fassung ohne das Neue tut,** wird bei jeder neuen Fassung fachlich entschieden. Es
gibt drei mögliche Antworten:

- Sie setzt einen **Ersatzwert** und läuft weiter.
- Sie liefert ein **geringeres Ergebnis**, etwa ein niedrigeres Niveau oder eine Angabe weniger.
- Sie **darf nicht mehr laufen** und wird abgeschaltet.

Die Antwort steht auf der Seite des jeweiligen Verfahrens unter [verfahren/](../verfahren/README.md).

**Eine neue Fassung bauen.** Ein einziger Handler bedient alle Fassungen. Er verzweigt nach
`ToolContext.version` nur dort, wo sich das Verhalten unterscheidet. Jede solche Stelle ist mit
„entfällt mit v1“ markiert. Für jede Fassung gibt es einen Controller im Paket `api.v<N>` des
Moduls, mit eigenen DTOs. Der alte Controller bleibt unverändert. Ein neues Feld in `missingFields`
braucht keine neue Schritt-Form. Eine geänderte Form bekommt eine neue `kind`. Ausgerollt wird
zuerst der Server mit beiden Fassungen, danach der Client mit der neuen. Ab dann kann der Server
nicht mehr hinter diese Fassung zurück. Die einzelnen Schritte zeigt
[15-beispiel-neues-verfahren-backend.md](../15-beispiel-neues-verfahren-backend.md) Abschnitt 10.

**Beispiel `enroll-sms@2`.** Fassung 2 verlangt zusammen mit der Telefonnummer eine Einwilligung
(`consent`): Der Nutzer stimmt zu, dass die Nummer gespeichert und für SMS-Codes genutzt wird.
Fassung 1 setzt den Ersatzwert „keine Einwilligung“ und läuft weiter. So kann eine App, die die
Checkbox nicht zeigen kann, weiterhin Nummern einrichten. Die Einwilligung wird nicht eigens
gespeichert. Der Eintrag `METHOD_ADDED` im Änderungsprotokoll nennt die Fassung, und daraus folgt,
ob eine Einwilligung vorlag. Die App spricht Fassung 1, der Web-Kanal Fassung 2
([Verfahren `sms`](../verfahren/sms.md)).

**Warum.** Ein Client wird getrennt vom Server ausgeliefert. Alte App-Versionen bleiben noch lange
im Einsatz, und jede von ihnen beherrscht je Tool genau eine Fassung. Ein Tool muss sich deshalb
auch inkompatibel ändern können, ohne dass die anderen Tools oder der Orchestrator davon betroffen
sind. Ein Mechanismus für beide Ebenen ist leichter zu verstehen als zwei verschiedene. Außerdem ist
so die erste Fassung kein Sonderfall.

**Erwogene Alternativen.**

- **Fassung in der toolId** (`enroll-sms-v2`). Dann gäbe es zwei Mechanismen nebeneinander. Die
  toolId in den Anfragen wiche von der gespeicherten Herkunft ab. Ein Namenszusatz müsste dafür
  reserviert werden, und die erste Fassung hätte keinen.
- **Orchestrator-Version auch im Tool-Pfad** (`/orchestrator/api/v1/tools/enroll-sms/v2/…`). Mit
  jeder neuen Orchestrator-Version würde sich jeder Tool-Pfad ändern. Die beiden Ebenen wären nicht
  mehr unabhängig.
- **Zwei Orchestrator-Versionen nebeneinander.** Der Kanal müsste sich seine Version merken, und
  jedes Tool müsste den Umschlag in beiden Formen ausgeben.
- **Eine Version für alles** (`/api/v2`). Alle Tools und der Umschlag müssten doppelt geführt
  werden, obwohl sich nur ein Tool ändert.

**Folgen.**

- Der neue Pfadraum `/tools/api` braucht einen eigenen Eintrag im Vite-Proxy. Compose und die
  OpenShift-Route leiten den ganzen Host weiter und brauchen keine Änderung.
- **Noch nicht gebaut** sind:
  - der Hinweis „App veraltet“ an Clients mit einer gesperrten oder ausgebauten Fassung
    (`DPoP-demo-kkod`),
  - die feste Antwort eines abgelösten Orchestrator-Pfads (`DPoP-demo-gd85`).
- Bis zum ersten Release wird statt einer neuen Fassung neu eingefroren (`publishApiVersion`), wie in
  ADR-50.
