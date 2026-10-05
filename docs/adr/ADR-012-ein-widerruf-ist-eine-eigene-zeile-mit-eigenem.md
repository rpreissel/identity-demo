# ADR-12: Ein Widerruf ist eine eigene Zeile mit eigener Quelle

**Status:** umgesetzt.

**Kontext**: Ein Konto sammelt **Angaben** über seinen Inhaber, etwa E-Mail-Adresse, Telefonnummer
oder Mitgliedsnummer. Zu jeder Angabe merkt es sich die **Quelle**, also wer sie geliefert hat: das
Personenverzeichnis (die Stammdaten der Versicherung), ein Prüfverfahren oder der Nutzer selbst
(siehe [Glossar](../glossar/glossar.md)). Die Angaben stehen in einem Log, in dem Zeilen nur
hinzukommen und nie geändert werden. So lässt sich später nachweisen, was wann galt. Manchmal muss
eine Angabe aber zurückgenommen werden, etwa wenn der Nutzer ein Verfahren entfernt oder das
Personenverzeichnis meldet, dass eine Mitgliedsnummer nicht mehr gilt. Die Frage ist, wie man eine
Angabe zurücknimmt, ohne das Log zu ändern.

**Entscheidung**: Ein zurückgenommener Wert wird in einer eigenen Zeile festgehalten:
`account.retraction(account_id, attribute_type, normalized_value, claim_source, reason, retracted_at)`.
Diese Zeile ist selbst eine Angabe mit eigener Quelle. Sie sagt, **wer** den Wert zurücknimmt, aus
welchem Grund und wann. Das Log der Angaben (`account.claim`) wird nur ergänzt, nie geändert. Welcher
Wert gilt, ergibt sich aus „Angaben minus Widerrufe“. Ist der Wert ein **Anker**, also eine Angabe,
über die sich das Konto eindeutig wiederfinden lässt, löscht der Server zusätzlich dessen Zeile in
`account.anchor`. Diese Tabelle hält nur den aktuellen Stand und ist kein Log.

Ein Widerruf macht nur Angaben ungültig, die **vor** ihm entstanden sind (`retractedAt >=
establishedAt` in `AccountClaimRepository.findEstablished`). Ein Wert, der danach neu bestätigt wird,
gilt wieder. Die Folge a → b → a endet also bei a. Diese eine Abfrage rechnet die Differenz aus. Alle
Stellen, die gültige Werte brauchen, rufen sie auf.

**Die Auslöser eines Widerrufs** (die vollständige Liste; andere ADRs verweisen hierher):

| Auslöser | Was zurückgenommen wird | Quelle |
|---|---|---|
| Ein Verfahren wird entfernt (`AccountDeletionService.revokeMethod` → `retractClaimsOf`) | die Angaben dieses eingerichteten Verfahrens, aber nur die mit `AttributeAuthority.MethodModule` (etwa `PHONE_NUMBER`, `PASSWORD_EXISTS`) | `ACCOUNT_MANAGEMENT` |
| Ein Anker wird an derselben Stelle ersetzt (`EMAIL`, `EID_RESTRICTED_ID`, `MEMBER_NUMBER`) | der alte Wert, Grund „anker-ersetzt“ ([ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)) | `ACCOUNT_MANAGEMENT` |
| Ein Attribut wird direkt zurückgenommen (`AccountService.retractAttribute`, `DELETE /channels/{id}/attributes/{attribute}`) | das Attribut samt Anker-Zeile ([ADR-24](ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md)) | `ACCOUNT_MANAGEMENT` |
| Das Personenverzeichnis meldet eine neue oder entfernte KVNR bzw. Mitgliedsnummer (`AccountService.applyDirectoryChange`) | der alte Wert ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)) | `PERSON_DIRECTORY` |

Die dritte Quelle, `OPERATOR`, ist für Eingriffe eines Menschen vorgesehen. Heute ruft sie niemand
auf. Widerrufe kommen **nie** über den Vertrag der Tools. Ein **Tool** ist ein abgeschlossener
Arbeitsschritt wie „SMS einrichten“; sein Ergebnis (`ToolOutcome`) kennt nur positive Ergebnisse.
Deshalb ist die Quelle eines Widerrufs bewusst ein eigener Typ (`RetractionSource`) und keine
`ClaimSource`.

Ein Verfahren muss wissen, welche Angaben beim Entfernen mit zurückgenommen werden. Deshalb enthält
jede Zeile im Log die `auth_method_id` des eingerichteten Verfahrens, bei dessen Einrichtung sie
entstand. Bei Tools zur Identifizierung ist dieser Wert `null`. Anker und Stammdaten bleiben bestehen,
auch wenn das Verfahren entfernt wird. Sonst hätte der Nutzer beim Entfernen des E-Mail-Verfahrens
die bestätigte Adresse verloren und damit auch die Anmeldung mit Passwort.

Das Log protokolliert **Änderungen, nicht Durchläufe**. `recordClaims` überspringt eine Angabe, die in
gleicher Form schon gilt: gleicher Typ, gleicher normalisierter Wert, gleiche Quelle und gleiches
eingerichtetes Verfahren. Das eingerichtete Verfahren gehört zu diesem Vergleich. Richtet ein neues
Verfahren einen schon bekannten Wert ein, wird er deshalb trotzdem protokolliert.

**Erwogene Alternative**: Markierungsspalten (`retracted_at`/`retracted_by`) direkt in der Zeile der
Angabe. Das ergäbe eine einzige Tabelle und die einfachste Abfrage. Es wäre aber die einzige Stelle,
an der eine Zeile im Log nachträglich geändert wird.

**Begründung**: Das Log ist die maßgebliche Quelle und wird nie überschrieben. Diese Regel darf keine
Ausnahme bekommen. Denn jede Änderung an einer bestehenden Zeile macht es schwerer, nachträglich zu
sagen, was wann galt. Eine Widerrufszeile belegt ihre Herkunft genauso wie eine Angabe. Außerdem
bleibt der Vertrag der Tools so frei von negativen Ergebnissen.

**Folgen und Kosten**: Es gibt zwei Formen statt einer. Der gültige Wert ist immer eine Differenz über
zwei Tabellen. Der Widerruf macht einen Wert nur ungültig, er löscht ihn nicht. Mit dem
normalisierten Wert steht in der Widerrufszeile sogar eine zweite lesbare Kopie. Wann beide Zeilen
nach Ablauf einer Aufbewahrungsfrist gemeinsam gelöscht werden, ist bewusst nicht mitentschieden
(siehe [Idee: Verschlüsselung und Aufbewahrung](../ideen/verschluesselung-differenzierte-aufbewahrung.md)).
Der Nachweis für das Audit ist davon nicht betroffen: `account.change_log` (IDENTIFIED) hält
Verfahren, Niveau und Zeitpunkt fest, ohne die Werte.

**Geschichte**: Anfangs hielten abgeleitete Spalten am Konto den aktuellen Wert, und nur eine Stelle
las Widerrufe. Mit [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)
entfielen die abgeleiteten Spalten. Die Auslöser kamen nacheinander hinzu: das Ersetzen eines Ankers
mit ADR-19, der direkte Widerruf mit ADR-24 und die Meldung des Personenverzeichnisses mit ADR-34.
