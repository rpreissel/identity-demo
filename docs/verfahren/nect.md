# Verfahren `nect`

Identifizieren beim Dienst Nect: Der Nutzer wählt dort Online-Ausweis, Reisepass oder EUDI-Wallet.
Das Ergebnis holt der Server danach einmalig selbst bei Nect ab, nie über den Client. Die
gelieferten Angaben bestätigt er wie bei `ident-eid` auf eigene Verantwortung
(`ClaimSource("ident-nect")`). Die Zuordnung zu einer Person folgt wie bei `ident-eid` über
`ident-kvnr` ([Verfahren `kvnr`](kvnr.md)).

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `ident-nect` | `IDENTIFICATION`, Claims zusätzlich Anschrift (`STREET_ADDRESS`, `POSTAL_CODE`, `LOCALITY`) und `NECT_RESTRICTED_ID` | 1 |

Faktoren `{possession,knowledge,inherence}`, höchstens `loa3`; welches Niveau und welches `amr`
(`nect-<verfahren>`) ein Durchlauf erreicht, hängt vom Dokument ab und wird je Durchlauf gemeldet
([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 3). Deklariert in
`tools/ident_nect/NectToolModule.kt`.

## Ablauf

`ident-nect` leitet zum Identifizierungsdienst Nect weiter (Sprungseite `/nect/`) und holt
das Ergebnis danach selbst ab (`NectIdent.redeem`). App oder Keycloak-Seite schicken den Nutzer auf
die Sprungseite; dort wählt er Online-Ausweis, Reisepass oder EUDI-Wallet. Zurück kommt er dorthin,
wo der Kanal den Fall hinbestellt hat. In beiden Kanälen gilt: Die Rücksprungadresse gehört
zum Fall. Die App nennt keine und bekommt `/app/`; der Web-Kanal nennt beim Aktivieren die
Action-URL des laufenden Keycloak-Schritts (`returnUri`), und der Orchestrator nimmt nur eine Adresse
unter einem konfigurierten Präfix an (`ident-nect.return-uri-prefixes`, ADR-47).

### Im Web-Kanal: ein Tool, das die Anmeldung verlässt

`ident-nect` schickt den Nutzer zu Nect und muss ihn zurückbekommen, ohne dass der Browser je mit dem
Orchestrator spricht. Die Erweiterung gibt dafür beim Aktivieren die **Action-URL des laufenden
Schritts** als `returnUri` mit (`POST /tools/api/ident-nect/v1?channel=…`, `WebToolRendererFactory.activationFields`),
also dieselbe Adresse, an die die Seite ihr Formular schicken würde: `login-actions/authenticate` mit
`session_code`, `execution`, `client_id` und `tab_id`. Nect hängt `nectCaseId` an. Ein GET auf diese
Adresse mit gültigem Code behandelt Keycloak wie den Formularversand des Schritts, und die Erweiterung
nimmt die Parameter der Anfrage als Eingabe des Tools (`OrchestratorNextDispatch.withQueryParams`,
ohne Keycloaks eigene). Der `PATCH` nennt die Fall-ID also als `nectCaseId`; das Tool nimmt den Namen
als Alias von `caseId` an. Der Orchestrator akzeptiert eine `returnUri` nur unter einem konfigurierten
Präfix, im Profil `keycloak` die öffentliche Keycloak-Adresse (`ident-nect.return-uri-prefixes`).
Warum das der Weg ist und nicht Identity Brokering: [ADR-47](../adr/ADR-047-nect-kehrt-auf-die-action-url-zurueck.md).

## Was `ident-nect` von Nect bekommt

Nect veröffentlicht keine Feldliste; was es weitergeben **kann**, begrenzt das Dokument selbst:

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
- **Anker**
  - *Online-Ausweis:* Pseudonym der Karte – je Diensteanbieter verschieden, bei Nect also Nects eigenes (`NECT_RESTRICTED_ID`, nicht das von `ident-eid`)
  - *Reisepass:* keiner – die Dokumentnummer wird nicht angefordert (§ 20 PAuswG / § 16 PassG)
  - *EUDI-Wallet:* ✗ – die PID trägt kein Pseudonym
- **Niveau / Faktortypen**
  - *Online-Ausweis:* `loa3`, Besitz + Wissen
  - *Reisepass:* `loa2`, Besitz + Biometrie (Lichtbildabgleich)
  - *EUDI-Wallet:* `loa3`, Besitz + Wissen

`ident-nect` fragt dasselbe an wie `ident-eid`: Name, Vorname, Geburtsdatum, Anschrift und den Anker
des Dokuments. Nect gibt nur weiter, was angefragt **und** vom Dokument lieferbar ist. Eine Person im
Personenverzeichnis findet `ident-nect` nicht; die Zuordnung folgt wie nach `ident-eid` über
`ident-kvnr`. Einen `PERSON_ID`-Claim liefert es deshalb nicht.

**Offen** sind die echte Anbindung und ein Anker für den Reisepass.

## In der Demo

Nect ist simuliert (`demoOnly`, Stellvertreter unter `/mock-nect`); ein echtes Ergebnis käme
serverseitig von Nect.
