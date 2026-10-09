# ADR-33: Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt

**Status:** umgesetzt.

Die Anwendung zeigt Nutzern viele Texte: Fehlermeldungen, Hinweise, Titel von Auswahlseiten. Diese
Texte sollen in mehreren Sprachen erscheinen, gut formuliert sein und trotzdem im Code leicht zu
finden und zu ändern bleiben. Diese ADR legt fest, wo die Texte stehen, wie sie übersetzt werden
und wie sie zum Nutzer kommen.

**Entscheidung**: Alle Texte des Backends, die Nutzer sehen, bleiben im Code. Sie stehen dort als
deutsche **Formulierung für Entwickler** in `Text("…")`, die sogenannte Vorlage. Veränderliche Teile
sind Platzhalter der Form `{name}`.

Das Backend schickt nie den fertigen Wortlaut an den Client, sondern nur eine Referenz
`{ key, args, texts }`. Der Schlüssel (`key`) besteht aus den Anfangswörtern der Vorlage als lesbarem
Kurznamen und den ersten 6 Hex-Zeichen ihres SHA-256-Hashwerts (siehe Nachtrag).

Jede Sprache, **auch Deutsch**, ist eine redigierte Fassung in
`src/main/resources/texts/<bundle>/texts_<lang>.properties`. Ein **Bundle** ist dabei eine Sammlung
von Texten, die zusammen ausgeliefert werden. Die Dateien schreibt der Claude-Code-Skill
`/translate-texts`, und zwar nach einem Prompt pro Sprache
(`.claude/skills/translate-texts/prompts/<lang>.md`).

Die Clients holen das Bundle beim Start per `GET …/texts/{lang}` mit ETag und setzen den Wortlaut
selbst ein. Ein ETag ist eine Kennung für den Inhalt; ändert sich nichts, muss der Client nichts neu
laden.

> **Nachtrag 2026-09-26: lesbare IDs, fehlende Übersetzung zeigt die Vorlage.**
>
> - **Die ID bleibt aus der Vorlage abgeleitet, wird aber lesbar.** Statt `cf9829c10e87` heißt sie
>   jetzt etwa `journey-trace-laden-fehlgeschlagen-cf9829`. Die Regel: Kleinbuchstaben, ä/ö/ü/ß
>   ausgeschrieben, alle anderen Zeichen werden zu `-`. Der Name ist höchstens 40 Zeichen lang und
>   endet an einer Wortgrenze. Danach folgen 6 Hex-Zeichen des SHA-256.
>
>   Die ID bleibt abgeleitet, weil nur so eine Übersetzung nicht unbemerkt veralten kann: Eine
>   geänderte Vorlage bekommt eine neue ID. Von Hand vergebene Schlüssel wurden verworfen. Sie hätten
>   eine eigene Prüfung gebraucht, und man hätte rund 1000 Texten einen Namen geben müssen.
>
>   Die Regel steht an sechs Stellen: `Text.idOf`, `KcText.idOf`, `texts.ts`, `text-catalog.mjs`,
>   `keycloak-theme/src/texts.ts` und `texts-per-page.mjs`. Gemeinsame Beispiele in `TextIdTest`,
>   `KcTextsTest` und `texts.test.tsx` sorgen dafür, dass alle sechs gleich rechnen. Zwei Vorlagen
>   mit derselben ID lässt `TextTranslationsTest` nicht durch.
> - **Fehlt eine Übersetzung, sieht man die Vorlage statt der ID.** Das Backend schickt die Vorlage
>   in `TextRef.template` mit, solange noch kein Bundle den Text in allen Sprachen enthält.
>   `TextTranslationsTest` warnt dann nur. Mit `-PstrictTexts`, gedacht für einen Build außerhalb
>   des Demomodus, schlägt der Test fehl. Ein neuer Text braucht also nicht sofort einen Lauf von
>   `/translate-texts`.

## Warum keine Schlüsselkataloge

Üblich ist, Texte aus dem Code auszulagern und im Code nur einen Schlüssel zu verwenden, etwa einen
Enum-Wert. Das hätte jede der rund 250 Stellen unlesbar gemacht: Man sähe `AuthSmsText.TAN_INVALID`
statt des Satzes. Außerdem hätte jede Änderung Arbeit an zwei Stellen verlangt.

Hier ist die Vorlage im Code selbst der Schlüssel. Ändert sich der Satz, ändert sich die ID. Die
Übersetzungen fehlen dann für die neue ID, und das fällt in der Prüfung auf.

## Warum auch Deutsch „übersetzt“ wird

Die Vorlage ist für Entwickler geschrieben. Sie enthält Umschrift (etwa „ae“ statt „ä“), Fachjargon
wie „Enrollment“ oder „loa2“ und teils Englisch. Was Versicherte tatsächlich lesen, formuliert
`prompts/de.md` daraus. So bleiben Fragen der Formulierung aus dem Code heraus, und Ton und Begriffe
sind an einer einzigen Stelle festgelegt.

## Einsammeln: aus den kompilierten Klassen, nicht aus dem Quelltext

Damit die Übersetzung vollständig ist, muss ein Werkzeug alle Vorlagen im Code finden. Das übernimmt
`TextCatalog` (Testcode). Es liest mit der Bibliothek ASM aus dem kompilierten Bytecode jeden Aufruf
von `Text.<init>`. Über eine Datenflussanalyse verfolgt es, welcher Wert als Vorlage ankommt.

Der Vorteil: Der Compiler hat die Literale bereits gelesen und zum Beispiel `"a " + "b"` zu einer
Konstanten zusammengefasst. Manchmal legt Kotlin die Vorlage zuerst in einer lokalen Variablen ab,
nämlich wenn ein Argument ein Inline-Lambda enthält. Auch diesen Umweg verfolgt die Analyse.

Ist eine Vorlage keine Konstante, scheitert `TextCatalogTest` und nennt Klasse, Methode und Zeile.
Die Regel „nur Literale, Parameter über `{name}`“ ist damit geprüft und nicht nur eine Vereinbarung.

Eine Prüfung zur Laufzeit ergänzt das: Im Test prüft `Text.toRef` jede ausgelieferte ID gegen den
Katalog. Nur das zu sammeln, was die Tests tatsächlich erzeugen, hätte seltene Fehlertexte
unbemerkt ausgelassen.

## Übersetzen per Claude Code, nicht im Build

Der Build braucht kein Netz und liefert immer dasselbe Ergebnis. Er übersetzt deshalb nicht selbst,
sondern prüft nur. `TextTranslationsTest` prüft je Bundle und Sprache:

- ob alles übersetzt ist,
- ob keine überzähligen Einträge vorhanden sind,
- ob die Platzhalter übereinstimmen.

Schlägt der Test fehl, nennt er den passenden Befehl `/translate-texts <lang>`. Der Skill bearbeitet
nur die neuen, geänderten und verwaisten Einträge. Die Dateien werden eingecheckt und im Diff
geprüft. Über jedem Eintrag steht dazu die Vorlage als Kommentar (`# Quelle:`).

## Bundles und Endpunkte

Jede Anwendung und jedes simulierte Fremdsystem liefert ihre Texte über einen eigenen Endpunkt aus:

- `app` für diese Anwendung: `GET /orchestrator/api/v1/texts/{lang}` (ohne DPoP, also ohne den sonst
  üblichen Nachweis, dass der Aufrufer den Schlüssel zum Token besitzt).
- Die simulierten Fremdsysteme bringen eigene Texte mit, so wie ein echter Dienst es auch täte:
  `/mock-nect/texts/{lang}`, `/mock-kobil/texts/{lang}`, `/mock-personenverzeichnis/texts/{lang}`.
  Zu welchem Bundle eine Vorlage gehört, richtet sich nach dem obersten Paket der Klasse, in der sie
  steht.
- Die Antwort ist eine Map `id → Wortlaut`. Dazu kommen ein starkes ETag über den Inhalt,
  `Cache-Control: no-cache` und `Content-Language`. Schickt der Client `If-None-Match` mit dem
  passenden ETag, antwortet der Server mit 304 (nichts geändert). Die Frage „Gibt es Neues?“ und das
  Herunterladen sind so eine einzige Anfrage.
- Bei einer unbekannten Sprache liefert der Server Deutsch. Die Region wird ignoriert (`en-GB` →
  `en`).

## Folgen

- **API-Vertrag:** Die Felder, die früher freien Text enthielten, enthalten jetzt eine Referenz
  (`TextRef`). Das sind `ErrorResponse.message` → `text`, `FailedAttemptStep.error`,
  `MessageStep.message`, `SelectMethodStep.title/description`, `Prompt.*` und
  `JourneyDebugStep.note`.
- **Modul `texts`:** Es ist eine Bibliothek ohne eigene Abhängigkeiten (`allowedDependencies = []`).
  Alle Module mit Nutzertexten deklarieren diese Abhängigkeit, auch die simulierten Fremdsysteme.
  Bei ihnen ist es die einzige.
- **Kein Text im gespeicherten Zustand der Journey** (also des geführten Ablaufs mit mehreren
  Schritten): Wo ein Aufrufer die Formulierung wählt, speichert der Zustand nur eine Variante
  (`ReIdentifyState.Wording`, `StepUpState.Reason`). Erst der Getter baut daraus den `Text`.
- **Fremdsysteme melden Gründe als Code** (`NectFailure`). Den Text dazu formuliert der Verbraucher
  selbst.
- **Ein Verb oder Satzteil ist nie ein Argument** (etwa `{write}` = „gesetzt“), denn jede Sprache
  beugt anders. Stattdessen gibt es zwei getrennte Vorlagen.

## Erweiterung: Texte der Clients (Frontend, Keycloak-Login)

Für die Texte der Clients gilt dasselbe Prinzip: dieselben Prompts, dieselbe Berechnung der ID. Je
Quelle unterscheiden sich nur drei Dinge: wie ein Text im Code markiert ist, welches Werkzeug ihn
einsammelt und wo der fertige Wortlaut liegt.

- **React-Frontend**
  - *Markierung:* `t("…")`, `<Tx text="… {x} …" x={<strong>…</strong>} />`
  - *Eingesammelt von:* `frontend/scripts/text-catalog.mjs` (liest den Syntaxbaum mit oxc, nur
    Literale erlaubt)
  - *Wortlaut liegt in:* den bestehenden Bundles: `entries/nect` → `nect`, `entries/personenverzeichnis` → `personenverzeichnis`, `kobilSdk.ts` → `kobil`, sonst `app`
- **Keycloak-Java**
  - *Markierung:* `KcText.t("…")`, `KcTexts.of(session, "…")`
  - *Eingesammelt von:* `KcTextCatalog` (ASM)
  - *Wortlaut liegt in:* `keycloak-theme/messages/messages_<lang>.properties` (der Build hängt sie an
    die Bundles des Theme-JARs an, [ADR-57](ADR-057-keycloakify-einziges-login-theme.md))
- **Login-Theme (Keycloakify)**
  - *Markierung:* `t("…")` in `keycloak-theme/src`
  - *Eingesammelt von:* `npm run texts:export`, gelesen von `KcTextCatalog`
  - *Wortlaut liegt in:* ebenda

Dazu gelten diese Regeln:

- **Frontend über den Orchestrator:** Die Texte des Frontends liegen im selben Bundle wie die Texte
  des Backends für diesen Client. Sie kommen zusammen mit ihnen per ETag, in einer einzigen Anfrage.
  So lassen sie sich ändern, ohne eine neue Version der App auszuliefern. Neue Vorlagen kommen
  natürlich erst mit neuem Code.
- **Ohne Wortlaut zeigt das Frontend die Vorlage.** Anders als bei Referenzen aus dem Backend kennt
  das Frontend seine Vorlagen selbst. Unit-Tests ohne geladenes Bundle prüfen deshalb weiterhin
  gegen die Vorlagen.
- **Seiten laden die Texte vor dem Code der App.** `main.tsx` lädt die App erst nach
  `loadAllTexts`. So findet `t()` den Wortlaut auch auf der obersten Ebene eines Moduls, etwa für
  Konstanten wie `SKIP_LABEL`.
- **Keycloak wählt die Sprache selbst** (Realm de/en, in `theme.properties` `locales=de,en`).
  `KcTexts` holt den Wortlaut aus den Messages des Themes und setzt die Platzhalter selbst ein,
  ohne MessageFormat.
- **Gleiche Vorlage, gleicher Hash, gleicher Wortlaut:** `TextTranslationsTest` prüft, dass eine ID
  in allen Bundles einer Sprache gleich formuliert ist. Name und Hinweis eines Tools (eines
  austauschbaren Bausteins für einen Schritt, etwa SMS-Code oder Online-Ausweis) stehen nur in
  seiner Moduldeklaration im Backend (`toolModule(name = …)`, `hint = …`). App und Login-Seite
  holen sie über `GET /tools/catalog` und lösen sie im Bundle `app` auf.
- **Nicht markiert** sind Oberflächen für Entwickler (Debug-Seitenleiste, Journey-Trace,
  `logEvent`), außerdem Code, Befehle, Pfade und Demo-Daten. Sie stehen als Werte von Platzhaltern
  im Satz.
