# ADR-26: Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt

**Status:** umgesetzt.

Worum es geht: Der Orchestrator, also der zentrale Server dieses Projekts, bietet eine HTTP-API an.
Der **API-Vertrag** beschreibt diese Schnittstelle: welche Anfragen es gibt, welche Felder sie haben
und welche Antworten zurückkommen. Zwei Stellen nutzen diesen Vertrag: das Frontend und die
Keycloak-Erweiterung. Früher hielt jede dieser Stellen ihre eigene, von Hand geschriebene Fassung des
Vertrags. Diese ADR legt fest, dass es nur noch eine Quelle gibt und alles andere daraus erzeugt
wird.

## Entscheidung

Die Quelle des API-Vertrags ist der Code. Alle anderen Fassungen werden daraus erzeugt:

- **`api/openapi.yaml`** ist ein erzeugtes Ergebnis, das trotzdem im Repository liegt (eingecheckt).
  Der `OpenApiSnapshotTest` schreibt die Datei aus dem laufenden Code
  (`./gradlew updateOpenApiSnapshot`). In der CI prüft `./gradlew checkOpenApiSnapshot`, dass die
  Datei zum Code passt. So wird jede gewollte Änderung am Vertrag im Review als Diff sichtbar.
- **`api/modules/<modul>.yaml`** entsteht im selben Lauf. Damit kann man Änderungen für jedes Modul
  einzeln prüfen. Schemas, die mehrere Module gemeinsam nutzen, werden dort nicht kopiert. Die
  Modul-Dateien verweisen stattdessen auf `../openapi.yaml#/components/schemas/…`.
- **`api/published/`** ist der eingefrorene, veröffentlichte Stand. Seit
  [ADR-50](ADR-050-api-versionierung-umschlag-und-tool.md) ist er zerlegt: eine Datei für den
  Umschlag und je Tool eine eigene Datei. In der CI prüft `checkPublishedApiCompatibility` (mit dem
  Werkzeug openapi-diff) jede Änderung gegen diesen Stand. Will man bewusst einen neuen Stand
  veröffentlichen, hebt man ihn mit `publishApiVersion` an.
- **Die Clients werden generiert.** Das Frontend erzeugt seine Typen mit dem OpenAPI Generator
  (`generateFrontendApiTypes`). Das Ergebnis liegt eingecheckt unter `frontend/src/generated`. Die
  CI prüft mit `git diff --exit-code`, dass es aktuell ist. Die Keycloak-Erweiterung erzeugt ihre
  Java-Modelle (`generateOrchestratorModels`). Diese sind nicht eingecheckt.
- **Pflichtfelder** ergeben sich aus der Quelle. Der `KotlinRequiredModelConverter` markiert eine
  Property als `required`, wenn sie in Kotlin nicht `null` sein kann und keinen Standardwert hat.

Einzelheiten stehen in [05-api.md](../05-api.md), Abschnitte 1 und 4.

## Begründung

Vorher lasen drei Stellen denselben Vertrag, und jede pflegte ihn von Hand:

- die Kotlin-DTOs im Orchestrator,
- `frontend/src/types.ts` im Frontend (209 Zeilen, die die DTOs nachbauten),
- das JSON-Parsing in `keycloak-extension`.

Nichts verband diese drei Fassungen. Änderte sich eine Antwort, bemerkte das jede Stelle zu einem
anderen Zeitpunkt. Die letzte Stelle bemerkte es oft erst zur Laufzeit.

**Erwogene Alternative:** prüfen statt generieren. Gemeint ist ein Test, der `types.ts` mit der Spec
vergleicht. Ein solcher Test meldet Abweichungen. Man pflegt aber weiterhin zwei Fassungen und tippt
jede Ergänzung zweimal. Wenn man generiert, kann es gar keine Abweichung geben.

## Folgen

- Es gibt eine Gradle-Abhängigkeit mehr (`org.openapi.generator`) und einen eingecheckten Stand des
  Frontend-Generators. Nach `updateOpenApiSnapshot` muss man zusätzlich `generateFrontendApiTypes`
  laufen lassen.
- Der Eintrag `servers` wird aus der Spec entfernt. Er enthielte den zufälligen Port des Tests und
  sagt nichts über den Vertrag aus.
- In der Keycloak-Erweiterung werden drei Dinge noch von Hand geparst: `restoreData`, die Liste der
  Verfahren und die Passwortprüfung.

## Geschichte

- Die erste Fassung dieser ADR nannte `api/openapi.yaml` „die einzige geschriebene Fassung“. Seit
  dem Snapshot-Test ist die Datei ein Erzeugnis des Codes. Erst danach kamen die Modul-Dateien, die
  eingefrorene v1 und die Modelle der Erweiterung dazu.
- Beim ersten Generieren fielen drei Fehler in der damaligen Spec auf:
  - `Set<FactorType>` wurde zu einem TypeScript-`Set`. Übertragen wird aber ein Array. Die DTOs
    nutzen deshalb jetzt `List`.
  - Der `@JsonAnyGetter`-Teil von `DemoInfo` stand als verschachteltes `values` in der Spec statt
    flach (`additionalProperties`).
  - Ein Tag hatte an mehreren Controllern verschiedene Beschreibungen. Tags werden jetzt an einer
    zentralen Stelle deklariert.
