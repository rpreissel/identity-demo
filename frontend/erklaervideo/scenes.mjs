// Script of the explainer video: each scene has a chapter, header, content (HTML) and beats (one spoken
// sentence or two each). An element with data-b="n" appears when beat n starts; data-d delays it in seconds.
// Clip timestamps (clip.from/to) refer to docs/media/demo.mp4 and must follow when it is recorded again.

/** The part of demo.mp4 a clip shows: above its caption bar (up to two lines), in the boxes' aspect ratio. */
const CROP = '1492:970:54:0'

export const CHAPTERS = ['Ausgangslage', 'Konzepte', 'Sicherheit', 'Architektur', 'Stand', 'Bewertung']

const phone = `<svg viewBox="0 0 24 24" class="ico"><rect x="6" y="2" width="12" height="20" rx="2.5"/><line x1="10" y1="18.5" x2="14" y2="18.5"/></svg>`
const monitor = `<svg viewBox="0 0 24 24" class="ico"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`
const check = `<svg viewBox="0 0 24 24" class="mark ok"><circle cx="12" cy="12" r="10"/><path d="M7 12.5l3.2 3.2L17 9"/></svg>`
const warn = `<svg viewBox="0 0 24 24" class="mark warn"><path d="M12 3l10 18H2z"/><line x1="12" y1="10" x2="12" y2="14.5"/><circle cx="12" cy="17.6" r=".6"/></svg>`

export const SCENES = [
  {
    id: 's01-titel', chapter: -1, plain: true,
    html: `
    <div class="title-screen">
      <div class="kicker" data-b="0">Ein Projekt in fünf Minuten</div>
      <h1 class="hero" data-b="0" data-d="0.3">Identity&nbsp;Demo</h1>
      <p class="lead" data-b="0" data-d="0.8">Registrieren und Anmelden für App und Website –<br>gesteuert von einem Orchestrator</p>
      <div class="agenda">
        <span data-b="1" data-d="0.0">Worum es geht</span>
        <span data-b="1" data-d="1.0">Wie es funktioniert</span>
        <span data-b="1" data-d="2.0">Wie weit es ist</span>
        <span data-b="1" data-d="2.9">Vor- und Nachteile</span>
      </div>
    </div>`,
    beats: [
      { t: 'Identity Demo: ein Projekt in fünf Minuten.' },
      { t: 'Worum es geht, wie es funktioniert, wie weit es ist, und wo die Haken liegen.' },
    ],
  },
  {
    id: 's02-ausgangslage', chapter: 0, kicker: 'Ausgangslage', title: 'Eine Versicherung, zwei Kanäle, ein Konto',
    html: `
    <div class="abs" style="left:150px;top:300px;width:420px" data-b="0">
      <div class="card row">${phone}<div><b>App</b><small>auf dem Smartphone</small></div></div>
    </div>
    <div class="abs" style="left:150px;top:470px;width:420px" data-b="0" data-d="0.4">
      <div class="card row">${monitor}<div><b>Website</b><small>im Browser</small></div></div>
    </div>
    <div class="abs pill accent" style="left:205px;top:640px" data-b="0" data-d="1.4">dieselben Konten</div>

    <div class="abs flowbox" style="left:680px;top:300px;width:470px" data-b="1">
      <div class="step-no">einmalig</div><h3>Registrieren &amp; ausweisen</h3>
      <div class="chips"><span>Freischaltcode per Brief</span><span>Online-Ausweis</span><span>Nect</span></div>
    </div>
    <div class="abs flowbox" style="left:680px;top:570px;width:470px" data-b="2">
      <div class="step-no">danach</div><h3>Anmelden</h3>
      <div class="chips"><span>SMS</span><span>Passwort</span><span>E-Mail</span><span>Gerät</span></div>
    </div>
    <svg class="abs edges" width="1920" height="1080" style="left:0;top:0">
      <path class="draw" data-b="1" d="M590 380 L 660 380"/>
      <path class="draw" data-b="2" d="M915 520 L 915 555"/>
      <path class="draw" data-b="3" d="M1170 650 L 1260 650"/>
      <path class="draw" data-b="3" data-d="1.6" d="M1600 650 L 1660 650"/>
    </svg>
    <div class="abs token" style="left:1280px;top:580px;width:300px" data-b="3">
      <small>Ziel</small><b>Access-Token</b>
    </div>
    <div class="abs card center" style="left:1680px;top:595px;width:170px" data-b="3" data-d="1.6"><b>Fach&shy;dienste</b></div>
    <div class="abs note" style="left:1280px;top:750px;width:560px" data-b="3" data-d="2.4">App und Website rufen die Fachdienste <b>direkt</b> auf – nicht über den Orchestrator.</div>`,
    beats: [
      { t: 'Eine Krankenversicherung bietet eine App und eine Website an. Beide nutzen dieselben Konten.' },
      { t: 'Wer neu ist, registriert sich einmal und weist sich dabei aus: etwa mit einem Freischaltcode per Brief oder dem Online-Ausweis.' },
      { t: 'Danach meldet man sich mit SMS, Passwort, E-Mail oder dem eigenen Gerät an.' },
      { t: 'Das Ziel ist immer ein Access-Token, mit dem App oder Website die Fachdienste der Versicherung direkt aufrufen.' },
    ],
  },
  {
    id: 's03-beteiligte', chapter: 0, kicker: 'Wer beteiligt ist', title: 'Der Orchestrator in der Mitte',
    html: `
    <svg class="abs edges" width="1920" height="1080" style="left:0;top:0">
      <path class="draw" data-b="1" d="M500 360 L 680 360"/>
      <path class="draw" data-b="2" d="M500 690 L 1420 690"/>
      <path class="draw both" data-b="2" data-d="1.5" d="M1590 630 L 1590 470 L 1250 470"/>
      <path class="draw sim" data-b="3" d="M960 590 L 960 840"/>
      <path class="draw sim" data-b="3" d="M290 840 L 1630 840"/>
    </svg>
    <div class="abs elabel" style="left:530px;top:310px" data-b="1">D-Pop</div>
    <div class="abs elabel" style="left:540px;top:640px" data-b="2">OIDC-Anmeldung</div>
    <div class="abs elabel" style="left:1300px;top:420px" data-b="2" data-d="1.5">Server zu Server</div>

    <div class="abs card row" style="left:150px;top:300px;width:350px" data-b="1">${phone}<div><b>App</b><small>jede Anfrage signiert mit dem Geräteschlüssel</small></div></div>
    <div class="abs card row" style="left:150px;top:630px;width:350px" data-b="2">${monitor}<div><b>Website</b><small>spricht nie direkt mit dem Orchestrator</small></div></div>
    <div class="abs core" style="left:680px;top:270px;width:560px;height:320px" data-b="0">
      <h3>Orchestrator</h3>
      <ul>
        <li data-b="0" data-d="1.6">entscheidet die nächsten Schritte</li>
        <li data-b="0" data-d="3.0">führt die Konten</li>
        <li data-b="0" data-d="4.0">bewertet die Nachweise</li>
      </ul>
    </div>
    <div class="abs card" style="left:1420px;top:630px;width:340px" data-b="2" data-d="0.8"><b>Keycloak</b><small>stellt die Tokens aus, ohne Kopie der Konten</small></div>
    ${['Personen&shy;verzeichnis', 'Nect', 'KOBIL', 'Online-Ausweis', 'SMS &amp; Mail'].map((n, i) =>
      `<div class="abs sim-box" style="left:${150 + i * 335}px;top:860px;width:280px" data-b="3" data-d="${0.4 + i * 0.5}"><b>${n}</b><small>simuliert</small></div>`).join('')}`,
    beats: [
      { t: 'Im Zentrum steht der Orchestrator. Er entscheidet, welche Schritte jemand durchläuft, führt die Konten und bewertet die Nachweise.' },
      { t: 'Die App spricht direkt mit ihm. Jede Anfrage ist mit dem Schlüssel des Geräts signiert, das ist DPoP.' },
      { t: 'Die Website meldet sich bei Keycloak an. Keycloak stellt die Tokens aus und fragt den Orchestrator, ohne die Konten zu kopieren.' },
      { t: 'Personenverzeichnis, Nect, KOBIL, Online-Ausweis sowie SMS- und Mail-Versand sind in der Demo simuliert.' },
    ],
  },
  {
    id: 's04-begriffe', chapter: 1, kicker: 'Drei Begriffe tragen das Modell', title: 'Intent, Journey, Tool',
    html: `
    <div class="abs concept" style="left:150px;top:240px;width:500px" data-b="0">
      <div class="tag">Intent</div><p>Was der Nutzer erreichen will</p>
      <div class="chips mono"><span>REGISTER</span><span>FAST_ACCESS</span><span>STEP_UP</span></div>
    </div>
    <div class="abs concept" style="left:710px;top:240px;width:500px" data-b="1">
      <div class="tag">Journey</div><p>Ein laufender Durchlauf zu einem Intent, mit festen Zuständen</p>
      <div class="mini" data-b="1" data-d="3.2">Je Intent ein Zustandsdiagramm – ein Test prüft es gegen den Code</div>
    </div>
    <div class="abs concept" style="left:1270px;top:240px;width:500px" data-b="2">
      <div class="tag">Tool</div><p>Ein einzelner Schritt, jeweils ein eigenes Modul</p>
      <div class="chips mono"><span>ident-…</span><span>enroll-…</span><span>auth-…</span></div>
    </div>
    <div class="abs chain-label" style="left:150px;top:600px" data-b="3">Beispiel: Journey <code>REGISTER</code></div>
    <div class="abs chain" style="left:150px;top:660px;width:1620px">
      ${[['ident-fsc', 'Freischaltcode'], ['confirm-email', 'E-Mail bestätigen'], ['enroll-sms', 'SMS einrichten'], ['enroll-password', 'Passwort einrichten']]
        .map(([id, l], i) => `${i ? `<div class="arrow" data-b="3" data-d="${i * 1.15 - 0.3}">→</div>` : ''}<div class="tool" data-b="3" data-d="${i * 1.15}"><code>${id}</code><span>${l}</span></div>`).join('')}
    </div>
    <div class="abs note" style="left:150px;top:850px;width:1620px" data-b="3" data-d="5.2">Der Orchestrator wählt nach jedem Tool den nächsten Schritt – so lange, bis das verlangte Niveau erreicht ist.</div>`,
    beats: [
      { t: 'Drei Begriffe tragen das Modell. Der Intent sagt, was der Nutzer will: sich registrieren, schnell anmelden oder sein Niveau anheben.' },
      { t: 'Eine Journey ist ein laufender Durchlauf zu einem Intent, mit festen Zuständen. Für jeden Intent gibt es ein Zustandsdiagramm, und ein Test prüft es gegen den Code.' },
      { t: 'Ein Tool ist ein einzelner Schritt, jeweils in einem eigenen Modul.' },
      { t: 'Eine Registrierung sieht zum Beispiel so aus: Freischaltcode, E-Mail bestätigen, SMS einrichten, Passwort einrichten.' },
    ],
  },
  {
    id: 's05-clip-app', chapter: 1, kicker: 'In der Demo', title: 'Registrieren in der App',
    clip: { from: 41.5, to: 89.5, box: [360, 196, 1200, 780], crop: CROP },
    html: `<div class="abs bezel" style="left:352px;top:188px;width:1216px;height:796px"></div>`,
    beats: [
      { t: 'So sieht das in der Demo aus. Links das Smartphone mit der App, rechts erklärt die Demo, was hinter den Kulissen passiert.' },
      { t: 'Die App weiß nie selbst, was als Nächstes kommt.' },
    ],
  },
  {
    id: 's06-next', chapter: 1, kicker: 'Die Regeln liegen im Backend', title: 'Der Client folgt „next“, er entscheidet nicht',
    html: `
    <div class="abs card big" style="left:150px;top:280px;width:420px" data-b="0"><b>App / Website</b><small>zeigt an, was verlangt wird</small></div>
    <div class="abs core" style="left:1350px;top:250px;width:420px;height:220px" data-b="0" data-d="0.6"><h3>Orchestrator</h3><ul><li>Journeys &amp; Zustände</li><li>Policy für die Niveaus</li></ul></div>
    <svg class="abs edges" width="1920" height="1080" style="left:0;top:0">
      <path class="draw" data-b="1" d="M590 320 L 1330 320"/>
      <path class="draw" data-b="1" data-d="1.4" d="M1330 410 L 590 410"/>
    </svg>
    <div class="abs elabel" style="left:820px;top:272px" data-b="1">Eingabe senden</div>
    <div class="abs json" style="left:720px;top:450px;width:640px" data-b="1" data-d="1.6">{
  <span class="k">"next"</span>: { <span class="k">"tool"</span>: <span class="v">"enroll-password"</span> },
  <span class="k">"stepData"</span>: { … }
}</div>
    <div class="abs routing" style="left:150px;top:470px;width:520px" data-b="1" data-d="3.4">
      <div class="rt-h">Feste Routing-Tabelle</div>
      <div><code>ident-fsc</code><span>→ Formular Freischaltcode</span></div>
      <div><code>enroll-sms</code><span>→ Formular TAN</span></div>
      <div class="hl" data-hl="1" data-d="5"><code>enroll-password</code><span>→ Formular Passwort</span></div>
    </div>
    <div class="abs banner ok" style="left:700px;top:760px;width:1070px" data-b="2">Neue Regel im Backend &nbsp;=&nbsp; <b>kein App-Update</b></div>`,
    beats: [
      { t: 'Das ist wichtig für die Fachlichkeit: Alle Regeln liegen im Backend.' },
      { t: 'Jede Antwort enthält ein „next“, eine reine Adresse auf den nächsten Schritt. App und Website folgen ihm über eine feste Tabelle. Sie entscheiden nie selbst.' },
      { t: 'Eine neue Regel braucht deshalb kein App-Update.' },
    ],
  },
  {
    id: 's07-niveaus', chapter: 2, kicker: 'Nicht jede Aktion verlangt dasselbe Vertrauen', title: 'Drei Sicherheitsniveaus',
    html: `
    <div class="abs stairs" style="left:150px;top:250px;width:980px;height:640px">
      <div class="lvl l1" data-b="1"><div class="lv">loa1</div><p>ein Verfahren<br><small>z. B. SMS oder Passwort</small></p></div>
      <div class="lvl l2" data-b="2"><div class="lv">loa2</div><p>zwei Faktortypen<br><small>SMS + Passwort, Gerät + Biometrie</small></p><em data-b="2" data-d="4.5">nötig z. B. für die Verwaltung der Verfahren</em></div>
      <div class="lvl l3" data-b="3"><div class="lv">loa3</div><p>starke Identifizierung<br><small>Online-Ausweis, Nect</small></p></div>
    </div>
    <div class="abs stepup" style="left:1180px;top:250px;width:590px" data-b="4"><b>Step-up</b><small>Reicht das Niveau nicht, verlangt der Orchestrator einen weiteren Nachweis.</small></div>
    <div class="abs caps" style="left:1180px;top:470px;width:590px" data-b="4" data-d="3">
      <div class="caps-h">Zwei Obergrenzen je Verfahren</div>
      <div data-b="4" data-d="4"><span>1</span>nie mehr, als es technisch hergibt</div>
      <div data-b="4" data-d="6"><span>2</span>nie mehr, als die Sitzung beim Einrichten nachgewiesen hatte</div>
      <small data-b="4" data-d="8.5">→ wer schwach angemeldet ist, verschafft sich kein dauerhaft höheres Niveau</small>
    </div>`,
    beats: [
      { t: 'Nicht jede Aktion verlangt dasselbe Vertrauen. Deshalb gibt es drei Sicherheitsniveaus.' },
      { t: 'Niveau eins: ein einzelnes Verfahren, etwa SMS.' },
      { t: 'Niveau zwei: zwei Verfahren verschiedener Art, zum Beispiel SMS plus Passwort, oder das Gerät mit Biometrie. Das verlangt etwa die Verwaltung der eigenen Verfahren.' },
      { t: 'Niveau drei gibt es nur über eine starke Identifizierung, mit Online-Ausweis oder Nect.' },
      { t: 'Reicht das Niveau nicht, folgt ein Step-up. Und ein Verfahren liefert nie mehr, als es technisch hergibt, und nie mehr, als die Sitzung bei seiner Einrichtung hatte. So verschafft sich niemand dauerhaft ein höheres Niveau.' },
    ],
  },
  {
    id: 's08-web', chapter: 2, kicker: 'Auf der Website', title: 'Keycloak führt, der Orchestrator entscheidet',
    clip: { from: 195.5, to: 234.5, box: [760, 210, 1040, 676], crop: CROP },
    html: `
    <div class="abs bezel" style="left:752px;top:202px;width:1056px;height:692px"></div>
    <ul class="abs bullets" style="left:150px;top:250px;width:560px">
      <li data-b="0">Standard-Keycloak mit eigener Erweiterung</li>
      <li data-b="0" data-d="2.2">zeigt die Verfahren, die der Orchestrator anbietet</li>
      <li data-b="0" data-d="4.6">dieselben Tool-Endpunkte wie die App</li>
      <li data-b="1" class="accent-li">QR-Login: die App gibt die Anmeldung frei</li>
    </ul>`,
    beats: [
      { t: 'Auf der Website führt Keycloak. Seine Anmeldeseite zeigt die Verfahren, die der Orchestrator anbietet, und reicht die Eingaben an dieselben Tool-Endpunkte weiter wie die App.' },
      { t: 'Auch die Anmeldung per QR-Code ist so gelöst: Die App bestätigt, die Website ist angemeldet.' },
    ],
  },
  {
    id: 's09-architektur', chapter: 3, kicker: 'Wie der Code aufgebaut ist', title: 'Ein Modulith mit klaren Grenzen',
    html: `
    <div class="abs mod-outer" style="left:150px;top:230px;width:1180px;height:700px" data-b="0"><div class="mod-h">Backend · Spring Boot Modulith · Kotlin</div></div>
    <div class="abs mod core-mod" style="left:180px;top:290px;width:540px;height:350px" data-b="1">
      <div class="mod-h">core</div>
      <div class="sub-mod">orchestrator<small>Kanäle, Journeys, Policy</small></div>
      <div class="sub-mod">account<small>Konten, Angaben, Anker, Verfahren</small></div>
      <div class="domain" data-b="3" data-d="3.6">Paket <code>domain</code>: die Regeln, ohne Framework</div>
    </div>
    <div class="abs mod" style="left:750px;top:290px;width:550px;height:96px" data-b="2"><div class="mod-h">contract</div><code class="lone">tool_api</code> <small class="inline">der schmale Vertrag</small></div>
    <div class="abs mod" style="left:750px;top:410px;width:550px;height:230px" data-b="2" data-d="0.8">
      <div class="mod-h">tools</div>
      <div class="chips mono small">${['ident_fsc', 'ident_eid', 'ident_nect', 'ident_kvnr', 'auth_sms', 'auth_password', 'auth_email', 'auth_device', 'auth_kobil', 'auth_qr', 'auth_invite'].map((t) => `<span>${t}</span>`).join('')}</div>
    </div>
    <div class="abs mod sim-mod" style="left:180px;top:665px;width:1120px;height:115px" data-b="3">
      <div class="mod-h">simulation · hinter Ports</div>
      <div class="chips mono small"><span>personenverzeichnis</span><span>nect</span><span>kobil</span><span>sms</span><span>mail</span></div>
    </div>
    <div class="abs mod" style="left:180px;top:790px;width:1120px;height:110px" data-b="3" data-d="1.2">
      <div class="mod-h">demo</div><div class="chips mono small"><span>demo_mode</span><span>demo_seed</span></div>
    </div>
    <div class="abs side" style="left:1380px;top:230px;width:390px">
      <div class="card" data-b="0" data-d="1.4"><b>frontend</b><small>React · App &amp; Demo-Oberfläche</small></div>
      <div class="card" data-b="0" data-d="2.0"><b>keycloak-extension</b><small>Java · fragt den Orchestrator</small></div>
      <div class="card" data-b="0" data-d="2.6"><b>api</b><small>OpenAPI, aus dem Code erzeugt</small></div>
      <div class="banner ok small" data-b="3" data-d="6.5">Architekturtests brechen den Build bei jeder Grenzverletzung</div>
    </div>`,
    beats: [
      { t: 'Technisch ist das Backend ein Spring-Boot-Modulith in Kotlin, mit einem React-Frontend und einer Erweiterung für Keycloak.' },
      { t: 'Den Kern bilden Orchestrator und Konto.' },
      { t: 'Jedes Verfahren ist ein eigenes Modul und kennt den Orchestrator nur über einen schmalen Vertrag. Es meldet bloß sein Ergebnis. Was eine Kombination von Nachweisen bedeutet, weiß allein die Policy.' },
      { t: 'Simulierte Fremdsysteme hängen hinter Ports. Die fachlichen Regeln liegen frameworkfrei im Paket domain, und Architekturtests lassen den Build scheitern, sobald eine Grenze verletzt wird.' },
    ],
  },
  {
    id: 's10-clip-trace', chapter: 3, kicker: 'Nachvollziehbar', title: 'Der Journey-Trace',
    clip: { from: 391, to: 399, box: [360, 196, 1200, 780], crop: CROP },
    html: `<div class="abs bezel" style="left:352px;top:188px;width:1216px;height:796px"></div>`,
    beats: [
      { t: 'Nachvollziehbar wird das im Journey-Trace: jeder Schritt mit Zeit, Zustand, Tool und Entscheidung, mitgeschrieben vom Orchestrator selbst.' },
    ],
  },
  {
    id: 's11-stand', chapter: 4, kicker: 'Umsetzungsstand', title: 'Drei Bereiche, drei Ansprüche',
    html: `
    <div class="abs col st-ok" style="left:150px;top:240px;width:520px;height:500px" data-b="1">
      <div class="badge">produktionsreif gemeint</div><h3>Backend-Kern</h3>
      <small class="what">Orchestrator, Konto, Verfahren, Keycloak-Erweiterung</small>
      <ul>
        <li data-b="1" data-d="5.2">Invarianten mit Testnachweis</li>
        <li data-b="1" data-d="6.5">46 Architekturentscheidungen</li>
        <li data-b="1" data-d="7.6">Sicherheitsaudit: hohe und mittlere Befunde bearbeitet</li>
        <li data-b="1" data-d="9.0">~32 000 Zeilen Kotlin, über 250 Testdateien</li>
      </ul>
    </div>
    <div class="abs col st-sim" style="left:700px;top:240px;width:520px;height:500px" data-b="2">
      <div class="badge">simuliert, hinter Ports</div><h3>Fremdsysteme</h3>
      <small class="what">Personenverzeichnis, Nect, KOBIL, eID, SMS, Mail</small>
      <ul><li data-b="2" data-d="3">Niveaus, die nur auf einer Simulation beruhen, gibt es nur im Demomodus</li><li data-b="2" data-d="5">Port-Verträge sagen, was ein echtes System zusagen muss</li></ul>
    </div>
    <div class="abs col st-demo" style="left:1250px;top:240px;width:520px;height:500px" data-b="3">
      <div class="badge">Vorführrahmen</div><h3>Frontends &amp; Umgebung</h3>
      <small class="what">React-App im Browser, Theme, Compose, OpenShift-Prototyp</small>
      <ul><li data-b="3" data-d="1.5">Schlüssel im Browser statt im Secure Element</li><li data-b="3" data-d="2.5">H2-Datenbank, selbstsigniertes TLS</li></ul>
    </div>
    <div class="abs banner bad" style="left:150px;top:780px;width:1620px" data-b="3" data-d="3.6">Noch keine echten Personendaten</div>`,
    beats: [
      { t: 'Wo steht das Projekt? Es gibt drei Bereiche mit verschiedenem Anspruch.' },
      { t: 'Der Backend-Kern samt Keycloak-Erweiterung ist produktionsreif gemeint und so geprüft: Invarianten mit Testnachweis, sechsundvierzig dokumentierte Architekturentscheidungen und ein Sicherheitsaudit, dessen hohe und mittlere Befunde bearbeitet sind.' },
      { t: 'Die Fremdsysteme sind simuliert. Verfahren, deren Niveau nur auf einer Simulation beruht, laufen ausschließlich im Demomodus.' },
      { t: 'Frontends und Betriebsumgebung sind Vorführrahmen. Deshalb gilt: noch keine echten Personendaten.' },
    ],
  },
  {
    id: 's12-vorteile', chapter: 5, kicker: 'Bewertung', title: 'Was für den Ansatz spricht',
    html: `
    <ul class="abs proscons" style="left:150px;top:250px;width:1620px">
      <li data-b="1">${check}<div><b>Eine Stelle für alle Regeln</b><small>gleich für App und Website, änderbar ohne App-Update</small></div></li>
      <li data-b="2">${check}<div><b>Neue Verfahren als Modul</b><small>jedes Tool beschreibt sich selbst, keine zentrale Liste</small></div></li>
      <li data-b="3">${check}<div><b>Das Gerät ist der Schlüssel</b><small>DPoP bindet jede Anfrage an den Geräteschlüssel</small></div></li>
      <li data-b="4">${check}<div><b>Keycloak bleibt Standard</b><small>liest die Konten beim Orchestrator, statt sie zu spiegeln</small></div></li>
      <li data-b="5">${check}<div><b>Nachvollziehbar</b><small>Journey-Trace, geprüfte Zustandsdiagramme, Architekturentscheidungen</small></div></li>
    </ul>`,
    beats: [
      { t: 'Was spricht für diesen Ansatz?' },
      { t: 'Es gibt eine Stelle für alle Regeln, gleich für App und Website.' },
      { t: 'Neue Verfahren kommen als eigenes Modul dazu, ohne zentrale Liste.' },
      { t: 'Das Gerät selbst ist der Schlüssel.' },
      { t: 'Keycloak bleibt Standard und hält keine Kopie der Konten.' },
      { t: 'Und jede Entscheidung ist nachvollziehbar: im Trace, in den geprüften Diagrammen und in den Entscheidungsdokumenten.' },
    ],
  },
  {
    id: 's13-nachteile', chapter: 5, kicker: 'Bewertung', title: 'Was dagegen spricht – und was offen ist',
    html: `
    <ul class="abs proscons" style="left:150px;top:250px;width:1620px">
      <li data-b="1">${warn}<div><b>Zentraler Baustein</b><small>ohne Orchestrator meldet sich niemand an – Verfügbarkeit ist Pflicht</small></div></li>
      <li data-b="2">${warn}<div><b>Eigene Keycloak-Erweiterung</b><small>muss bei jedem Keycloak-Update mitgezogen werden</small></div></li>
      <li data-b="3">${warn}<div><b>Echte Fremdsysteme fehlen</b><small>erst sie liefern die Nachweise, etwa für loa3; begonnen ist Nect</small></div></li>
      <li data-b="4">${warn}<div><b>App im Browser</b><small>produktiv nötig: native App mit hardwaregestütztem Schlüsselspeicher</small></div></li>
      <li data-b="5">${warn}<div><b>Offene Grundsatzentscheidungen</b><small>Schlüsselverwaltung, Verschlüsselung, Zieldatenbank, mehrere Instanzen</small></div></li>
    </ul>`,
    beats: [
      { t: 'Und was spricht dagegen?' },
      { t: 'Der Orchestrator ist ein zentraler Baustein. Fällt er aus, meldet sich niemand an.' },
      { t: 'Die Keycloak-Erweiterung ist Eigenbau und muss bei jedem Keycloak-Update mitgezogen werden.' },
      { t: 'Echte Fremdsysteme sind noch nicht angebunden. Erst sie liefern die Nachweise, etwa für Niveau drei.' },
      { t: 'Die App läuft heute im Browser. Produktiv braucht sie einen hardwaregestützten Schlüsselspeicher.' },
      { t: 'Offen sind außerdem Grundsatzfragen wie Schlüsselverwaltung, Verschlüsselung und Zieldatenbank.' },
    ],
  },
  {
    id: 's14-naechster-schritt', chapter: 5, kicker: 'Wie es weitergeht', title: 'Der nächste Schritt: Abstimmung mit euch',
    html: `
    <div class="abs qgrid" style="left:150px;top:260px;width:1620px">
      <div class="q" data-b="1"><span>?</span>Stimmen Begriffe, Konten und Zustände?</div>
      <div class="q" data-b="1" data-d="2.4"><span>?</span>Stimmen die Regeln für die Niveaus?</div>
      <div class="q" data-b="1" data-d="4.2"><span>?</span>Stimmen die Abläufe je Intent?</div>
    </div>
    <div class="abs note big" style="left:150px;top:520px;width:1620px" data-b="2">Was jetzt abweicht, ändert das Modell – <b>und das ist jetzt noch billig.</b></div>
    <div class="abs path" style="left:150px;top:680px;width:1620px" data-b="3">
      <div class="path-h">Einstieg für Fachexperten</div>
      <div class="path-row">
        <span data-b="3" data-d="0.3">Beispiel-Story „Mara“</span><i>→</i>
        <span data-b="3" data-d="1.6">Orchestrierung</span><i>→</i>
        <span data-b="3" data-d="2.6">Journey-Diagramme</span><i>→</i>
        <span data-b="3" data-d="3.6">Entscheidungen</span>
      </div>
      <div class="url" data-b="3" data-d="5">rpreissel.github.io/identity-demo</div>
    </div>`,
    beats: [
      { t: 'Der wichtigste nächste Schritt ist die Abstimmung mit euch.' },
      { t: 'Stimmen Begriffe, Konten und Zustände? Stimmen die Regeln für die Niveaus und die Abläufe je Intent?' },
      { t: 'Was jetzt abweicht, ändert das Modell, und das ist jetzt noch billig.' },
      { t: 'Der beste Einstieg ist die Beispiel-Story von Mara, danach die Orchestrierung, die Journey-Diagramme und die Entscheidungen. Alles steht in der Doku, auch als Website.' },
    ],
  },
]
