# Idee: Nect im Web-Kanal über den Rücksprung auf die Action-URL des laufenden Schritts

Status: **Konzept, nicht umgesetzt, Entscheidung offen** (Stand 2026-09-28, Issue
`DPoP-demo-p6rl`). Das Dokument beschreibt, wie `ident-nect` in den Web-Kanal käme, ohne dass
Keycloak etwas anderes tun müsste, als es für seine eigenen Anmeldeseiten ohnehin tut. Drei Vorgaben
liegen dem Entwurf zugrunde: In Keycloak wird nur benutzt, was Keycloak selbst vorsieht; der
App-Kanal behält seinen heutigen Weg, und beide Wege teilen sich so viel Code wie möglich; Nect
spricht kein OIDC, deshalb darf der Entwurf nichts voraussetzen, was Nect nicht kann.

Die naheliegende Idee, Nect als Identity Provider in Keycloak anzubinden, wurde an den Quellen von
Keycloak 26.6.4 geprüft und verworfen. Die Gründe stehen in Abschnitt 2, damit sie nicht noch einmal
durchdacht werden müssen.

---

## 1) Ausgangslage

`ident-nect` legt bei Nect einen Fall an, schickt den Browser auf die Sprungseite und holt das
Ergebnis danach selbst ab ([03-tool-architektur.md](../03-tool-architektur.md), „Was `ident-nect`
von Nect bekommt“). Der Rücksprung von Nect trägt nur die Fall-ID (`?nectCaseId`); wer sich
ausgewiesen hat, erfährt der Server allein über `redeem`, genau einmal und nur für den Fall, den diese
Tool-Sitzung eröffnet hat ([port-vertraege.md](../port-vertraege.md), Abschnitt Nect).

Das funktioniert heute nur im App-Kanal, und zwar aus vier Gründen:

1. Die Rücksprungadresse ist fest auf `/app/` verdrahtet. Das Tool kennt den Kanal nicht und könnte
   den Nutzer nirgendwo anders hinschicken.
2. Die App überlebt den Ausflug zu Nect, weil Kanal-ID und DPoP-Schlüssel auf dem Gerät liegen. Nach
   der Rückkehr nimmt sie ihren Kanal wieder auf und meldet die Fall-ID. Der Web-Kanal hat nichts
   dergleichen; seine Bindung ist die Auth-Session von Keycloak.
3. Im Web-Kanal kommt ein Ablauf nur über das Formular des laufenden Schritts voran. Und der Browser
   spricht nie mit dem Orchestrator (ADR-7), also muss jeder Rücksprung bei Keycloak landen.
4. Die Keycloak-Erweiterung hat keinen `WebToolRenderer` für `ident-nect`; das Theme führt die
   `toolId` nicht in `availableTools`, und so wird sie im Web-Kanal nie angeboten.

## 2) Was Keycloak für „raus und wieder rein“ anbietet, und was davon ohne Konto geht

Ein Identifizierungsschritt bei der Registrierung hat eine Eigenheit, die in Keycloak selten ist: Es
gibt noch keinen Nutzer. Das Konto entsteht erst im Laufe der Journey, und Keycloak legt keine
eigenen Nutzer an (ADR-38). Jeder Weg zurück in den Ablauf muss also ohne Nutzer auskommen. Drei
Mechanismen wurden geprüft.

**Identity Brokering (Nect als OIDC-IdP, mit einem Adapter davor).** Verworfen. Ein Broker-Login
ist in Keycloak immer die Anmeldung eines Nutzers, nie ein Schritt in einem laufenden Ablauf:

- Nach der Rückkehr sucht Keycloak den Nutzer über den Federated-Identity-Link. Findet es keinen,
  setzt es den Ablauf zurück: Alle Auth-Notes, der Ausführungsstand der Executions, die
  User-Session-Notes und der angemeldete Nutzer werden gelöscht, und der First-Broker-Login-Flow
  beginnt. Der ursprüngliche Browser-Flow läuft nie wieder an; die Anmeldung endet über die
  Broker-Flows und die Required Actions.
- Der First-Broker-Login-Flow muss mit einem gesetzten Nutzer enden, sonst bricht Keycloak mit
  einer Fehlerseite ab. Unmittelbar danach schreibt Keycloak immer einen Federated-Identity-Link
  zwischen diesem Nutzer und der IdP-Identität, bei Nutzern aus einer Federation ohne Import in die
  Tabelle `fed_user_federated_identity`. Bei einer Registrierung gibt es diesen Nutzer nicht.
- Der einzige Ausweg ohne Nutzer sind „Transient Users“. Das Feature ist als EXPERIMENTAL markiert,
  und ein späterer Tausch gegen das Orchestrator-Konto scheitert, weil Keycloak beim Setzen eines
  anderen Nutzers einen USER_CONFLICT wirft.
- Der Link selbst widerspricht ADR-18: Nect löst keine Person auf, die Zuordnung läuft über
  `ident-kvnr`. Keycloak würde eine Nect-Identität dauerhaft an ein Konto binden. Bei stabilem `sub`
  (Online-Ausweis) meldete der nächste Nect-Login direkt als dieses Konto an, ohne die Journey. Bei
  wechselndem `sub` (Reisepass) entstünde bei jeder Identifizierung ein neuer Link, und der zweite
  Link desselben Kontos zum selben IdP endet mit „already linked“.

**Action Tokens** (`login-actions/action-token`, die Links für Passwort zurücksetzen, E-Mail
bestätigen und IdP-Verknüpfung). Verworfen. Sie sind sonst ein gutes Muster: signiert, an
Root-Session und Tab gebunden, einmal nutzbar, und der Handler bekommt die bestehende Auth-Session
mit allen Notes. Aber Keycloak prüft vor jedem Handler, dass der im Token genannte Nutzer existiert
und aktiv ist. Ohne Nutzer gibt es kein Action Token.

**Die Action-URL des laufenden Schritts.** Gewählt. Jede Anmeldeseite von Keycloak schickt ihr
Formular an `login-actions/authenticate` mit `session_code`, `execution`, `client_id` und `tab_id`.
Ein Aufruf dieser Adresse mit gültigem Code gilt als Aktion des laufenden Schritts, auch als GET:
Keycloak ruft `action()` des Authenticators, der den Schritt gerade hält, mit der unveränderten
Auth-Session. Es braucht keinen Nutzer, keinen zusätzlichen Endpunkt und keine Suche nach der
Session; der Code ist an sie gebunden, und ohne das Session-Cookie des Browsers ist er wertlos.
Genau diesen Code gibt Keycloak selbst aus der Hand, wenn es einen Nutzer zu einem fremden Identity
Provider schickt: Er steckt, verpackt, im `state`-Parameter und kommt mit der Antwort zurück. Der
Entwurf tut mit Nect nichts anderes.

## 3) Der Entwurf

Der Web-Kanal gibt dem Tool beim Start eine Rücksprungadresse mit. Das ist die einzige Stelle, an
der sich die beiden Kanäle unterscheiden.

1. **Start.** Der Orchestrator bietet `ident-nect` an, wenn das Theme es in `availableTools` führt.
   Beim Aktivieren schickt die Erweiterung eine `returnUri` mit: die Action-URL des laufenden
   Schritts, die sie auch für ihre Formulare erzeugt. Der Orchestrator legt den Nect-Fall mit dieser
   Adresse als Callback an und antwortet wie heute mit dem Schritt `redirect` (`jumpUrl`, `caseId`).
   Ohne `returnUri` bleibt es beim heutigen `/app/`, deshalb ändert sich für die App nichts.
2. **Seite.** Ein gewöhnlicher `WebToolRenderer` für `ident-nect` zeigt „Weiter zu Nect“ mit dem
   Sprunglink und den üblichen Knöpfen für Zurück und Abbrechen (FreeMarker und Keycloakify, wie die
   anderen Tool-Seiten). Kein automatischer Redirect: Der Nutzer verlässt die Anmeldung mit einem
   Klick, so wie er einen Anbieter auf einer Login-Seite anklickt.
3. **Bei Nect.** Unverändert, Sprungseite und Ergebnis wie heute. Am Ende hängt Nect `nectCaseId`
   an die Rücksprungadresse; dass diese schon Parameter trägt, kann die Simulation bereits.
4. **Rückkehr.** Der Browser ruft die Action-URL auf. Keycloak erkennt den gültigen Code und ruft
   `action()` des Orchestrator-Authenticators. Der liest die Fall-ID aus der Anfrage, gibt sie wie
   jede andere Tool-Eingabe per `PATCH` an die Tool-Sitzung, und der Orchestrator löst den Fall bei
   Nect ein, genau einmal und nur für diese Tool-Sitzung. Die Antwort trägt den nächsten Schritt,
   und die Erweiterung zeichnet ihn wie nach jedem Formular.
5. **Bindung.** Die Auth-Session ist dieselbe, die Tab-ID auch, und damit die Kanal-ID und der
   `channel_anchor`. Es gibt nichts zu retten und nichts zu prüfen, was Keycloak nicht schon prüft.

Was dabei sichtbar bleibt: Der Rücksprung sagt „fertig“, nicht „wer“. Wer die Rücksprungadresse
kennt, aber nicht das Session-Cookie des Browsers hat, bekommt von Keycloak die Seite „Sitzung nicht
gefunden“. Wer den Aufruf im selben Browser wiederholt, trifft auf einen verbrauchten Code; Keycloak
zeigt dann die aktuelle Seite noch einmal, wie bei jedem doppelt abgeschickten Formular. Der Fall
selbst lässt sich ohnehin nur einmal einlösen.

Zwei Grenzen gehören dazu. Erstens muss die Rückkehr im selben Browser stattfinden; das ist im
Web-Kanal ohnehin die Regel, und Nect führt den Nutzer auf seiner Sprungseite selbst zurück. Zweitens
darf der Ausflug nicht länger dauern als Keycloaks Login-Timeout, denn so lange gilt der Code. Wer
Nect für eine Videoidentifizierung anbindet, prüft diesen Wert im Realm.

## 4) Ein Kern, zwei Hüllen

Das Tool-Modul `ident_nect` bleibt die einzige Nect-Anbindung. Fall führen, Ergebnis einlösen und
deuten (Verfahren, Niveau `loa3` für Online-Ausweis und EUDI-Wallet, `loa2` für den Reisepass,
`amr = nect-<verfahren>`, Claims, das eingeschränkte Pseudonym nur beim Online-Ausweis) sind für
beide Kanäle derselbe Code. Neu ist nur, dass die Rücksprungadresse nicht mehr eine Konstante ist,
sondern zum Fall gehört: Sie kommt beim Start mit, wird in der Tool-Sitzung gemerkt und bei einem
`retry` für den neuen Fall wiederverwendet. Der Orchestrator nimmt eine Rücksprungadresse nur aus
einem Kanal an, der sie nennen darf, und prüft sie gegen die konfigurierte Basis-URL des jeweiligen
Kanals, für Keycloak also gegen dessen `login-actions`.

Die Hüllen: Die App gibt keine Adresse mit und bekommt `/app/`; die Erweiterung gibt ihre
Action-URL mit. Beide melden die Fall-ID mit demselben `PATCH`. Die erlaubten Abhängigkeiten des
Moduls bleiben `tool_api`, `nect` und `texts`, die Architekturtests ändern sich nicht. Die echte
Anbindung an Nect (Issue `DPoP-demo-v033`) trifft nur den Nect-Port hinter dem Kern, also eine Stelle
für beide Kanäle. Ein OIDC-Adapter ist dafür nicht nötig.

## 5) Was in Keycloak dazukommt

- Ein `WebToolRendererFactory` für `ident-nect` mit den beiden Seiten. Damit erscheint die `toolId`
  in `availableTools`, und der Orchestrator bietet das Tool im Web-Kanal an.
- Die Erweiterung gibt beim Aktivieren die `returnUri` mit.
- `action()` nimmt die Eingaben eines Tool-Schritts nicht nur aus dem Formular, sondern auch aus
  den Parametern der Anfrage entgegen, damit `nectCaseId` den `PATCH` erreicht.
- Kein `RealmResourceProvider`, kein Identity Provider, keine Broker-Flows, keine Realm-Migration
  für den Ablauf selbst. Ob der Realm einen Subflow für `loa3` bekommt, ist eine eigene Frage
  (Abschnitt 6).

## 6) Offene Fragen

1. **`loa3` im Web-Kanal.** Der Realm kennt heute nur Subflows für LoA 1 und LoA 2. Ob und wo
   `ident-nect` angeboten wird, hängt daran, ob der Web-Kanal ein drittes Niveau bekommt, oder ob
   Nect dort zunächst nur als Identifizierung in der Registrierung dient.
2. **Login-Timeout.** Wie lange darf ein Nect-Vorgang dauern, und passt der Wert im Realm dazu?
3. **Verlassen ohne Ergebnis.** Kommt der Nutzer nie zurück, bleibt der Fall offen und die
   Tool-Sitzung im Schritt `redirect`; beides läuft mit dem Kanal aus. Reicht das, oder soll die
   Seite „Weiter zu Nect“ nach der Rückkehr per Zurück-Taste einen `retry` anbieten?
4. **Soll die App die Adresse auch explizit mitgeben,** damit `/app/` keine Sonderregel mehr ist?

## 7) Nächste Schritte (falls die Umsetzung gewünscht ist)

1. Im Tool-Modul die Rücksprungadresse vom Konstanten zum Feld des Falls machen: beim Start
   entgegennehmen, in der Tool-Sitzung merken, bei `retry` wiederverwenden, gegen die erlaubte Basis
   prüfen. `IdentNectToolHandlerTest` und `IdentNectIntegrationTest` erweitern.
2. Renderer und Seiten in beiden Themes, `returnUri` beim Aktivieren, Parameter der Anfrage im
   `action()`.
3. Ein Playwright-Test im Web-Kanal von der Registrierung über die Sprungseite zurück bis zum
   nächsten Schritt.
4. Doku nach der Entscheidung: `03-tool-architektur.md`, die Lücke in `05-api.md`, der Satz „nur im
   App-Kanal“ in `06-ablaeufe.md` und `journeys/register.md`, ein Absatz zur Rücksprungadresse in
   `port-vertraege.md` und eine ADR mit den Befunden aus Abschnitt 2.
