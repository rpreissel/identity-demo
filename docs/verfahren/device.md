# Verfahren `device`

Ein Schlüsselpaar, das auf dem Gerät erzeugt wird und es nie verlässt, entsperrt mit der
Sicherheitsabfrage des Systems (PIN oder Biometrie). Anders als bei `sms`, `email` und `password`
gibt es hier kein Geheimnis, das der Server ausstellt.

## Tools

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-device` | `ENROLLMENT` | 1 |
| `auth-device` | `KNOWN_ACCOUNT_AUTH` | 1 |

Faktoren `{possession,knowledge,inherence}`, höchstens `loa2`; `onePerDevice`, also ein aktiver
Eintrag je Gerät (`allowsMultipleInstances = true`, [03-tool-architektur.md](../03-tool-architektur.md)
Abschnitt 5). Deklariert in `tools/auth_device/DeviceToolModule.kt`. Das Credential liegt in
`auth_device.enrollment`.

## Der Nachweis

Das Credential *ist* ein auf dem Gerät erzeugtes, nicht exportierbares Schlüsselpaar (ECDSA P-256),
unabhängig vom DPoP-Schlüssel des Kanals; `enroll-device` lehnt einen Geräteschlüssel ab, der der
DPoP-Schlüssel ist. Der Client weist den Besitz mit einem selbst signierten
`device-proof+jwt` nach. Der ist genauso aufgebaut wie ein DPoP-Proof (`jwk` im Header,
`htm`/`htu`/`iat`/`jti`), hat aber einen eigenen `typ` und zusätzlich den Claim `userVerification`
(`pin` oder `biometric`). Welcher Wert darin steht, bestimmt bei jedem Versuch die
Sicherheitsabfrage des Systems. `DeviceProofValidator` prüft den Geräte-Proof
eigenständig; `DpopValidator` ist dafür bewusst nicht erweitert
([Projektrahmen](../08-projektrahmen.md) A11). Er nutzt aber dieselben Bausteine
(`JwkThumbprintService`, Schutz gegen Wiederholung per Thumbprint und `jti`).

Eine Nonce vom Server ist nicht nötig: `htu` bindet den Nachweis bereits an die URL mit der
einmaligen `toolSessionId`.

## Ablauf

- **`enroll-device`**: Der Controller prüft den Nachweis und gibt nur die bestätigten Felder des
  öffentlichen Schlüssels (`DevicePublicKey`: `kty`/`crv`/`x`/`y`/`thumbprint`) an den Handler
  weiter. Das Modul bekommt also nie ein Krypto-Objekt, nur Zeichenketten
  ([Tool-Architektur](../03-tool-architektur.md) Abschnitt 7). Es legt einen Datensatz in
  `auth_device.enrollment` an; die Referenz ist `EnrollmentRef(type="auth_device.enrollment", id=...)`.
  Als `reference` meldet es den Kanalschlüssel; ihn zeigt `GET /app/channels/device-link` in
  `boundCredentials` ([05-api.md](../05-api.md) Abschnitt 3a).
- **`auth-device`**: Löst die aktive Enrollment-Referenz auf (wie `auth-sms`) und vergleicht den
  Thumbprint des vorgelegten Schlüssels mit dem gespeicherten. Weichen sie ab, meldet es
  `Failed("Geraet nicht erkannt")`, ohne zu verraten, welches Gerät erwartet wurde. Angeboten wird
  es zum Anmelden nur auf dem Gerät mit dem passenden Schlüssel; deaktivieren lässt es sich von
  überall ([05-api.md](../05-api.md) Abschnitt 3a, `GET /channels/{channelSessionId}`).
- **loa2 in einem Schritt**: `maxAcr=loa2`, `factorTypes={possession,knowledge,inherence}` – Besitz
  des Schlüssels plus Wissen (PIN) oder Inhärenz (Biometrie) aus einem einzigen Durchlauf
  ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2). Welche zwei Faktoren ein
  Durchlauf erbracht hat, meldet das Tool je nach Entsperren selbst (Abschnitt 3 dort). Dass das
  Gerät nie mehr liefert,
  als die Sitzung beim Einrichten nachgewiesen hatte, sichern die allgemeinen Prüfungen ab, kein
  eigener Code: `enrolledUnderAcr` begrenzt es (ADR-5). Nach einer Identifizierung steht die Sitzung
  schon auf `loa2`, und vor jedem späteren Einrichten verlangt
  `AuthIntent.MANAGE_AUTH_METHODS` über die Schwelle `selfServiceAcrFloor` denselben Nachweis (für
  ein nie identifiziertes Konto nur loa1).

Wird das Gerät mit `intent=register` an ein anderes Konto neu verknüpft, wird das bisherige
Geräte-Credential des alten Kontos für genau diesen Schlüssel deaktiviert bzw. gelöscht
([05-api.md](../05-api.md) Abschnitt 3a, Parameter `intent`). Zum Zusammenspiel mit der
Geräteverknüpfung: [09-dpop.md](../09-dpop.md).

Im Web-Kanal gibt es dieses Verfahren nicht: Es hat keinen `WebToolRenderer`, denn der
Schlüsselspeicher eines Telefons ist von einer Anmeldeseite aus nicht erreichbar
([05-api.md](../05-api.md) Abschnitt 3b).

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag: fehlender oder ungültiger `deviceProof` (Signatur,
Wiederholung, `htm`/`htu`/`iat`) -> `401`, auf demselben Weg (`DpopValidationException`) wie bei
DPoP-Proofs. Ein falscher Schlüssel bei `auth-device` -> `Failed`, kein Fehlerstatus; das ist ein
gewöhnlicher Fehlversuch wie eine falsche TAN.

## In der Demo

Die Sicherheitsabfrage des Systems ist in der Demo simuliert. Weil der Server den Wert von
`userVerification` nur als Behauptung der App sieht, sind beide Tools nur im Demomodus verfügbar
(`demoOnly`, [ADR-36](../adr/ADR-036-niveaus-und-ihre-nachweise.md)).
