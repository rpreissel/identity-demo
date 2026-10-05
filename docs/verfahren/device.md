# Verfahren `device`

**Was es ist:** Der Nutzer meldet sich mit seinem Smartphone selbst an. Beim Einrichten erzeugt die
App auf dem Gerät einen Schlüssel, der das Gerät nie verlässt. Bei jeder Anmeldung entsperrt der
Nutzer diesen Schlüssel mit der Sicherheitsabfrage des Systems, also mit PIN oder Biometrie (etwa
Fingerabdruck).

**Wozu es dient:** Es ist ein bequemes Anmeldeverfahren für die App. Weil es den Besitz des Geräts
und zugleich PIN oder Biometrie nachweist, erreicht es in einem einzigen Schritt das Niveau `loa2`.
Auf der Website gibt es dieses Verfahren nicht.

Technisch ist das Verfahren ein Schlüsselpaar, das auf dem Gerät erzeugt wird und es nie verlässt.
Entsperrt wird es mit der Sicherheitsabfrage des Systems (PIN oder Biometrie). Anders als bei
`sms`, `email` und `password` gibt es hier kein Geheimnis, das der Server ausstellt.

Begriffe wie Tool, Rolle, Fassung, Faktortyp und Niveau erklärt die
[Übersicht der Verfahren](README.md). Weitere Begriffe stehen im [Glossar](../glossar/glossar.md).

## Tools

Das Verfahren hat zwei Tools: eines zum Einrichten (`enroll-device`) und eines zum Anmelden eines
bekannten Kontos (`auth-device`).

| toolId | Rolle | Fassungen |
|---|---|---|
| `enroll-device` | `ENROLLMENT` | 1 |
| `auth-device` | `KNOWN_ACCOUNT_AUTH` | 1 |

Das Verfahren kann drei Faktortypen erbringen: Besitz, Wissen und Inhärenz
(`{possession,knowledge,inherence}`). Es liefert höchstens das Niveau `loa2`. Es gilt
`onePerDevice`, also ein aktiver Eintrag je Gerät (`allowsMultipleInstances = true`,
[03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 5). Deklariert ist das Verfahren in
`tools/auth_device/DeviceToolModule.kt`. Das Credential, also das gespeicherte Merkmal für die
Anmeldung, liegt in `auth_device.enrollment`.

## Der Nachweis

Das Credential *ist* ein Schlüsselpaar (ECDSA P-256), das auf dem Gerät erzeugt wird und sich nicht
exportieren lässt.

Es ist unabhängig vom DPoP-Schlüssel des Kanals. **DPoP** ist ein Standard, mit dem die App bei
jeder Anfrage belegt, dass sie einen bestimmten Schlüssel besitzt. Der **Kanal** ist die Verbindung
der App zum Orchestrator, also zum Server dieses Projekts. `enroll-device` lehnt einen
Geräteschlüssel ab, der zugleich der DPoP-Schlüssel ist.

Der Client weist den Besitz des Schlüssels mit einem selbst signierten `device-proof+jwt` nach. Der
ist genauso aufgebaut wie ein DPoP-Proof (`jwk` im Header, `htm`/`htu`/`iat`/`jti`). Er hat aber
einen eigenen `typ` und zusätzlich den Claim `userVerification` (`pin` oder `biometric`). Welcher
Wert darin steht, bestimmt bei jedem Versuch die Sicherheitsabfrage des Systems.

`DeviceProofValidator` prüft den Geräte-Proof eigenständig. `DpopValidator` ist dafür bewusst nicht
erweitert ([Projektrahmen](../08-projektrahmen.md) A11). Er nutzt aber dieselben Bausteine
(`JwkThumbprintService`, Schutz gegen Wiederholung per Thumbprint und `jti`).

Eine Nonce vom Server ist nicht nötig. Denn `htu` bindet den Nachweis bereits an die URL mit der
einmaligen `toolSessionId`.

## Ablauf

- **`enroll-device`**: Der Controller prüft den Nachweis. Danach gibt er nur die bestätigten Felder
  des öffentlichen Schlüssels (`DevicePublicKey`: `kty`/`crv`/`x`/`y`/`thumbprint`) an den Handler
  weiter. Das Modul bekommt also nie ein Krypto-Objekt, nur Zeichenketten
  ([Tool-Architektur](../03-tool-architektur.md) Abschnitt 7). Es legt einen Datensatz in
  `auth_device.enrollment` an. Die Referenz darauf ist
  `EnrollmentRef(type="auth_device.enrollment", id=...)`. Als `reference` meldet es den
  Kanalschlüssel. Ihn zeigt `GET /app/channels/device-link` in `boundCredentials`
  ([05-api.md](../05-api.md) Abschnitt 3a).
- **`auth-device`**: Das Tool löst die aktive Enrollment-Referenz auf (wie `auth-sms`). Dann
  vergleicht es den Thumbprint des vorgelegten Schlüssels mit dem gespeicherten. Weichen sie ab,
  meldet es `Failed("Geraet nicht erkannt")`, ohne zu verraten, welches Gerät erwartet wurde. Zum
  Anmelden wird das Tool nur auf dem Gerät mit dem passenden Schlüssel angeboten. Deaktivieren lässt
  es sich dagegen von überall aus ([05-api.md](../05-api.md) Abschnitt 3a,
  `GET /channels/{channelSessionId}`).
- **loa2 in einem Schritt**: `maxAcr=loa2`, `factorTypes={possession,knowledge,inherence}`. Ein
  einziger Durchlauf belegt den Besitz des Schlüssels und dazu entweder Wissen (PIN) oder Inhärenz
  (Biometrie) ([03-tool-architektur.md](../03-tool-architektur.md) Abschnitt 2). Welche zwei
  Faktoren ein Durchlauf erbracht hat, meldet das Tool selbst, je nachdem, wie entsperrt wurde
  (dort Abschnitt 3).

  Das Gerät liefert nie mehr, als die Sitzung beim Einrichten nachgewiesen hatte. Dafür sorgen die
  allgemeinen Prüfungen, kein eigener Code: `enrolledUnderAcr` begrenzt das Niveau (ADR-5). Nach
  einer Identifizierung steht die Sitzung schon auf `loa2`. Vor jedem späteren Einrichten verlangt
  `AuthIntent.MANAGE_AUTH_METHODS` über die Schwelle `selfServiceAcrFloor` denselben Nachweis. Für
  ein Konto, das nie identifiziert wurde, reicht dabei loa1.

Wird das Gerät mit `intent=register` neu mit einem anderen Konto verknüpft, wird das bisherige
Geräte-Credential des alten Kontos für genau diesen Schlüssel deaktiviert bzw. gelöscht
([05-api.md](../05-api.md) Abschnitt 3a, Parameter `intent`). Wie das Verfahren mit der
Geräteverknüpfung zusammenspielt, steht in [09-dpop.md](../09-dpop.md).

Im Web-Kanal, also bei der Anmeldung auf der Website, gibt es dieses Verfahren nicht. Es hat keinen
`WebToolRenderer`, denn der Schlüsselspeicher eines Telefons ist von einer Anmeldeseite aus nicht
erreichbar ([05-api.md](../05-api.md) Abschnitt 3b).

## Fehlerfälle

Zusätzlich zum allgemeinen Vertrag gilt:

- Fehlender oder ungültiger `deviceProof` (Signatur, Wiederholung, `htm`/`htu`/`iat`) -> `401`. Der
  Fehler läuft auf demselben Weg (`DpopValidationException`) wie bei DPoP-Proofs.
- Falscher Schlüssel bei `auth-device` -> `Failed`, kein Fehlerstatus. Das ist ein gewöhnlicher
  Fehlversuch wie eine falsche TAN.

## In der Demo

Die Sicherheitsabfrage des Systems ist in der Demo simuliert. Der Server sieht den Wert von
`userVerification` nur als Behauptung der App. Deshalb sind beide Tools nur im Demomodus verfügbar
(`demoOnly`, [ADR-36](../adr/ADR-036-niveaus-und-ihre-nachweise.md)).
