# ADR-56: Vault Transit ist das Ziel für den Schlüsseldienst

**Status:** entschieden 2026-10-09 (Issue `DPoP-demo-61kp`), Umsetzung offen (`DPoP-demo-lmt9`).
Ergänzt [ADR-54](ADR-054-schluesseldienst-simuliert.md), das die Wahl des echten Dienstes
zurückgestellt hatte.

**Entscheidung.** Hinter dem Port `KeyService` aus ADR-54 steht im Betrieb Vault Transit. Das
betrifft alle Schlüssel des Orchestrators, die heute in der Simulation liegen: den
Umschlagschlüssel `identity-kek` (ADR-52, ADR-53, ADR-55) und die Signaturschlüssel
`keycloak-response` und `keycloak-client-auth:<Client>` (ADR-7, ADR-9, ADR-25). In der Demo bleibt
die Simulation (`simulation/kms`).

**Begründung.** Der Port ist nach Transit geschnitten. Die Simulation verwendet dieselben Begriffe
wie Transit, der Adapter muss also nichts übersetzen:

- `encrypt` und `decrypt` mit `context` binden ein Chiffrat an seinen Zweck (`transit/encrypt`,
  `transit/decrypt` mit Derivation).
- `sign` liefert mit `marshaling_algorithm=jws` direkt `R || S`.
- Versionen, Rotation und Zurückziehen entsprechen `transit/keys/<name>/rotate` und
  `min_decryption_version`.
- Die öffentlichen Schlüssel aller gültigen Versionen liefert `transit/keys/<name>`.

Der Dienst läuft selbst betrieben neben Keycloak und Orchestrator, also ohne Bindung an einen
Cloud-Anbieter. OpenBao bietet dieselbe Transit-Schnittstelle und ginge ohne Änderung am Adapter.

**Nicht Teil dieser Entscheidung.** Die Schlüssel, die Keycloak selbst hält, zum Beispiel die
Realm-Schlüssel und den Schlüssel für die Peer-Auth-Assertion. Sie bleiben bei Keycloaks eigenen
Key-Providern.

**Alternativen.**

- *Ein Cloud-KMS (AWS, GCP, Azure).* Die Fähigkeiten wären ähnlich. Es bindet den Betrieb aber an
  einen Anbieter, den niemand genannt hat. Ein Adapter dafür bliebe hinter demselben Port möglich.
- *Ein HSM über PKCS#11.* Es kennt keine Versionen mit Rotation und Zurückziehen im Sinne von
  ADR-54, die müssten wir selbst bauen. Der Aufwand stünde in keinem Verhältnis, solange kein
  Betreiber ein HSM verlangt.

**Folgen.**

- `DPoP-demo-lmt9` baut den Adapter mit Zeitlimits, einem Leistungsschalter und der Anmeldung an
  Vault (z. B. AppRole oder Kubernetes-Auth). Tabellen und Versionen bleiben, wie ADR-54 sie
  beschreibt.
- Das Projekt ist eine reine Demo. Der Adapter entsteht erst, wenn eine Instanz mit echten Daten
  laufen soll. Bis dahin verweigert `ProductionModeCheck` außerhalb des Demomodus den Start mit
  der Simulation.
