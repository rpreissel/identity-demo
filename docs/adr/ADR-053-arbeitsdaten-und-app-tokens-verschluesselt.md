# ADR-53: Arbeitsdaten der Tools und App-Tokens liegen verschlüsselt

**Status:** umgesetzt 2026-10-06 (Issue `DPoP-demo-6pzy`). Baut auf
[ADR-52](ADR-052-umschlagverschluesselung-des-claim-logs.md) auf und ergänzt
[ADR-49](ADR-049-arbeitsdaten-der-tools-am-orchestrator.md) und
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

**Entscheidung.** Zwei Bestände des Orchestrators, die bisher im Klartext lagen, sind verschlüsselt.
Beide nutzen die Schlüsselhierarchie aus ADR-52, aber auf verschiedene Art, weil ihre Lebensdauer
verschieden ist.

**1. Arbeitsdaten der Tools** (`orchestrator.tool_session.data`, ADR-49). Sie sind kurzlebig,
oft ohne Konto (eine Registrierung liest die Karte, bevor das Konto entsteht) und werden nie
einzeln gelöscht, sondern mit der Tool-Sitzung nach `tool-session.retention`. Deshalb:

- Ein **Datenschlüssel je Aufbewahrungsklasse und Tag** (`orchestrator.data_key`, Klasse
  `TOOL_SESSION`, Id `TOOL_SESSION:<Datum>`), eingepackt mit dem KEK. `RetentionClassKeys` legt den
  Schlüssel für heute und morgen beim Start und stündlich vorab an; nur wenn das ausblieb, legt ihn
  der erste Schreibvorgang des Tages in seiner eigenen Transaktion an. Jede Zeile nennt den
  Schlüssel ihres Tages (`data_key_id`).
- Die Werte bleiben JSON, liegen aber als Chiffrat (AES-256-GCM) unter diesem Schlüssel, mit
  Tool-Sitzung und Datentyp als geprüften Zusatzdaten. `ToolSessionDataService` ist die eine
  Stelle, die ver- und entschlüsselt. Der Codec serialisiert weiterhin nur.
- Nach `retire_after` (Tagesende plus sieben Tage plus `tool-session.retention`) braucht keine Zeile
  den Schlüssel mehr. `RetentionJob` löscht ihn stündlich mit den übrigen Fristen. Was dann noch
  unter ihm liegt, in einer verpassten Zeile oder einer Sicherung, ist unlesbar.
- Eine Zeile wird nie neu verschlüsselt. Ein Schlüsselwechsel ist das Anlegen des nächsten
  Tagesschlüssels, nichts weiter.

**2. Tokens der App-Sitzungen** (`orchestrator.app_token_session.access_token`, `refresh_token`).
Sie gehören zu einem Konto und sind ein Zwischenspeicher, kein Nachweis. Deshalb:

- Verschlüsselt **unter dem Hauptschlüssel des Kontos** (ADR-52), über einen eigenen abgeleiteten
  Teilschlüssel, mit dem Zweck (`app-token:access`, `app-token:refresh`) und der Id der App-Sitzung
  als geprüften Zusatzdaten. Ein Refresh-Token lässt sich nicht als Access-Token vorlegen und nicht
  in eine andere Sitzung desselben Kontos kopieren. Ein Token, das sich so nicht öffnen lässt, gilt
  als nicht vorhanden; die Sitzung holt ein neues bei Keycloak.
- `AppTokenVault` ist die eine Stelle, die liest und schreibt. Leeren braucht keinen Schlüssel.
- Mit dem Konto verschwindet der Hauptschlüssel, und mit ihm jedes Token in Sicherungen.

**Wo der Schlüssel herkommt.** Der KEK bleibt im Modul `account` hinter `MasterKeyWrapper`. Das
Modul bietet dem Orchestrator zwei Fassaden im Wurzelpaket an: `DataKeyWrapping` (Schlüssel
erzeugen, ein- und auspacken, AES-GCM) und `AccountDataCipher` (ver- und entschlüsseln unter dem
Hauptschlüssel eines Kontos). So gibt es eine Implementierung von AES-GCM und später einen
KMS-Adapter für alle Schlüssel. `ProductionModeCheck` prüft die KEK-Versionen von
`orchestrator.data_key` genauso wie die der Konten.

**Alternativen.**

- *Arbeitsdaten unter dem Hauptschlüssel des Kontos.* Scheitert daran, dass viele Durchläufe
  beginnen, bevor es ein Konto gibt. Verworfen.
- *Ein Datenschlüssel je Tool-Sitzung.* Einfach, aber eine Schlüsselzeile je Durchlauf bei
  1.500 bis 2.500 Zeilen je Sekunde in der Spitze (14-stand Abschnitt 7), ohne Nutzen: Die Zeile
  wird ohnehin als Ganzes gelöscht. Verworfen.
- *Ein einziger Schlüssel für alle Arbeitsdaten, nie gewechselt.* Keine Grenze für den Schaden
  eines entwendeten Schlüssels und kein Mittel gegen alte Sicherungen. Verworfen.
- *Tokens unter dem Tagesschlüssel wie die Arbeitsdaten.* Ginge, aber Tokens haben ein Konto, und
  der Kontoschlüssel gibt die bessere Grenze: Ein Konto, ein Schlüssel, ein Schaden.

**Folgen und Kosten.**

- Jeder Journey-Schritt ver- und entschlüsselt die Arbeitsdaten einmal, lokal. Der Tagesschlüssel
  liegt ausgepackt im Speicher der Instanz, bis er verfällt; danach liest ihn keine Instanz mehr,
  auch nicht eine, die ihn noch im Speicher hatte. Legen zwei Instanzen denselben Tagesschlüssel
  gleichzeitig an, gewinnt die Datenbank (Primärschlüssel); die andere Anfrage scheitert einmal
  und findet den Schlüssel beim nächsten Versuch. Das Vorab-Anlegen hält diesen Fall fern.
- Jede Token-Anfrage packt den Hauptschlüssel des Kontos einmal aus (`AppTokenVault.forSession`),
  wie beim Claim-Log. Mit einem KMS gilt dieselbe Rechnung wie dort (ADR-52, Zwischenspeicher je
  Instanz). Das Verlängern einer Sitzung liest dafür das Access-Token, um seinen Ausstellungszeitpunkt
  zu kennen; ein eigener Zeitstempel in der Zeile würde das ersparen und ist ein möglicher Folgeschritt.
- Die Spalten `data`, `access_token` und `refresh_token` sind binär. Tests, die sie lesen, gehen
  über `ToolSessionDataService` beziehungsweise `AppTokenVault`.
- Bestehende Zeilen verlieren beim Umstieg ihren Inhalt: Arbeitsdaten offener Durchläufe und
  zwischengespeicherte Tokens. Beides wird neu erzeugt, ein Durchlauf beginnt von vorn.
- Nicht verschlüsselt bleiben `account.anchor` und die Simulationen (`personenverzeichnis`, `nect`,
  `kobil`). Mobilnummer und KOBIL-PIN folgen in [ADR-55](ADR-055-hauptschluessel-je-journey-verfahrensgeheimnisse-versiegelt.md),
  der Signaturschlüssel des Orchestrators in [ADR-54](ADR-054-schluesseldienst-simuliert.md).
