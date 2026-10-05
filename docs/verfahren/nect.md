# Verfahren `nect`

**Was es ist:** Der Nutzer weist beim externen Dienst Nect nach, wer er ist. Dort wählt er, womit:
mit dem Online-Ausweis, mit dem Reisepass oder mit der EUDI-Wallet, der digitalen
Brieftasche der EU für Ausweisdaten.

**Wozu es dient:** Es ist ein Verfahren zur **Identifizierung**, also zur Feststellung, wer jemand
wirklich ist. Je nach Dokument erreicht es das Niveau `loa2` oder `loa3`. Anders als ein
Anmeldeverfahren wird es nicht im Konto eingerichtet.

Das Ergebnis holt der Server danach einmalig selbst bei Nect ab, nie über den Client. Die
gelieferten Angaben bestätigt er wie bei `ident-eid` auf eigene Verantwortung
(`ClaimSource("ident-nect")`). Die Zuordnung zu einer Person im Personenverzeichnis, den Stammdaten
der Versicherung, folgt wie bei `ident-eid` über `ident-kvnr` ([Verfahren `kvnr`](kvnr.md)).

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat ein einziges Tool, `ident-nect`. Außer den üblichen Identitätsangaben liefert es
die Anschrift und `NECT_RESTRICTED_ID` als **Claims**, also als Angaben über den Kontoinhaber, die im
Konto gespeichert werden.

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-nect` | `IDENTIFICATION`, Claims zusätzlich Anschrift (`STREET_ADDRESS`, `POSTAL_CODE`, `LOCALITY`) und `NECT_RESTRICTED_ID` | 1 |

Das Verfahren kann drei Faktortypen erbringen: Besitz, Wissen und Inhärenz
(`{possession,knowledge,inherence}`). Es liefert höchstens das Niveau `loa3`. Welches Niveau und
welches `amr` (`nect-<verfahren>`) ein Durchlauf erreicht, hängt vom Dokument ab. Das Tool meldet
es je Durchlauf ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 3). `amr` ist die Liste
der Verfahren, mit denen sich ein Nutzer in der laufenden Sitzung angemeldet hat. Deklariert ist das
Verfahren in `tools/ident_nect/NectToolModule.kt`.

## Ablauf

`ident-nect` leitet zum Identifizierungsdienst Nect weiter (Sprungseite `/nect/`). Danach holt es
das Ergebnis selbst ab (`NectIdent.redeem`).

1. App oder Keycloak-Seite schicken den Nutzer auf die Sprungseite.
2. Dort wählt er Online-Ausweis, Reisepass oder EUDI-Wallet.
3. Danach kommt er an die Adresse zurück, die der Kanal für diesen Fall festgelegt hat.

Der **Kanal** ist die Verbindung des Nutzers zum Orchestrator, entweder über die App oder über die
Website. In beiden Kanälen gilt: Die Rücksprungadresse gehört zum Fall.

- Die App nennt keine Adresse und bekommt `/app/`.
- Der Web-Kanal nennt beim Aktivieren die Action-URL des laufenden Keycloak-Schritts (`returnUri`).
  Der Orchestrator nimmt nur eine Adresse unter einem konfigurierten Präfix an
  (`ident-nect.return-uri-prefixes`, ADR-47).

### Im Web-Kanal: ein Tool, das die Anmeldung verlässt

Auf der Website führt **Keycloak** die Anmeldung. `ident-nect` schickt den Nutzer zu Nect und muss
ihn zurückbekommen, ohne dass der Browser je direkt mit dem Orchestrator spricht.

Dafür gibt die Keycloak-Erweiterung beim Aktivieren die **Action-URL des laufenden Schritts** als
`returnUri` mit (`POST /tools/api/ident-nect/v1?channel=…`, `WebToolRendererFactory.activationFields`).
Das ist dieselbe Adresse, an die die Seite ihr Formular schicken würde: `login-actions/authenticate`
mit `session_code`, `execution`, `client_id` und `tab_id`. Nect hängt `nectCaseId` an.

Ein GET auf diese Adresse mit gültigem Code behandelt Keycloak wie den Formularversand des Schritts.
Die Erweiterung nimmt die Parameter der Anfrage als Eingabe des Tools, ohne Keycloaks eigene
Parameter (`OrchestratorNextDispatch.withQueryParams`). Der `PATCH` nennt die Fall-ID also als
`nectCaseId`. Das Tool nimmt diesen Namen als zweiten Namen (Alias) von `caseId` an.

Der Orchestrator akzeptiert eine `returnUri` nur unter einem konfigurierten Präfix. Im Profil
`keycloak` ist das die öffentliche Keycloak-Adresse (`ident-nect.return-uri-prefixes`). Warum das
der Weg ist und nicht Identity Brokering, steht in
[ADR-47](../adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).

## Was `ident-nect` von Nect bekommt

Nect veröffentlicht keine Feldliste. Was Nect weitergeben **kann**, begrenzt das Dokument selbst.

Quellen: Online-Ausweis nach [§18 PAuswG](https://www.gesetze-im-internet.de/pauswg/__18.html), Reisepass nach [ICAO 9303](https://www.icao.int/publications/doc-series/doc-9303) (DG1), EUDI-Wallet nach dem [PID-Rulebook](https://github.com/eu-digital-identity-wallet/eudi-doc-attestation-rulebooks-catalog/blob/main/rulebooks/pid/pid-rulebook.md).

- **Auswahl der Daten**
  - *Online-Ausweis:* je Zugriffsrecht
  - *Reisepass:* keine – der Chip wird ganz gelesen
  - *EUDI-Wallet:* je Attribut; der Nutzer darf ablehnen
- **Name, Vorname, Geburtsdatum**
  - *Online-Ausweis:* ✓
  - *Reisepass:* ✓ in MRZ-Schreibweise (`MUELLER`, ggf. gekürzt)
  - *EUDI-Wallet:* ✓
- **Anschrift**
  - *Online-Ausweis:* ✓, Straße und Hausnummer in einem Feld
  - *Reisepass:* ✗
  - *EUDI-Wallet:* optional
- **Anker** (eine Angabe, über die sich ein Konto eindeutig wiederfinden lässt)
  - *Online-Ausweis:* Pseudonym der Karte – je Diensteanbieter verschieden, bei Nect also Nects eigenes (`NECT_RESTRICTED_ID`, nicht das von `ident-eid`)
  - *Reisepass:* keiner – die Dokumentnummer wird nicht angefordert (§ 20 PAuswG / § 16 PassG)
  - *EUDI-Wallet:* ✗ – die PID enthält kein Pseudonym
- **Niveau / Faktortypen**
  - *Online-Ausweis:* `loa3`, Besitz + Wissen
  - *Reisepass:* `loa2`, Besitz + Biometrie (Lichtbildabgleich)
  - *EUDI-Wallet:* `loa3`, Besitz + Wissen

`ident-nect` fragt dasselbe an wie `ident-eid`: Name, Vorname, Geburtsdatum, Anschrift und den Anker
des Dokuments. Nect gibt nur weiter, was angefragt **und** vom Dokument lieferbar ist.

Eine Person im Personenverzeichnis findet `ident-nect` nicht. Die Zuordnung folgt wie nach
`ident-eid` über `ident-kvnr`. Deshalb liefert `ident-nect` keinen `PERSON_ID`-Claim.

**Offen** sind die echte Anbindung und ein Anker für den Reisepass.

## In der Demo

Nect ist simuliert (`demoOnly`, Stellvertreter unter `/mock-nect`). Ein echtes Ergebnis käme
serverseitig von Nect.
