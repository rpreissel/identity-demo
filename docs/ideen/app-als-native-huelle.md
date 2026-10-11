# Idee: Die App als native Hülle

**Worum es geht.** Die App des App-Kanals soll realistischer werden, ohne dass dafür eine eigene
native App entsteht. Ein dünnes macOS-Programm zeigt die vorhandene Oberfläche `/app/` in einer
`WKWebView`. Nur das Signieren übernimmt es selbst: Die Schlüssel liegen in der **Secure Enclave**,
und vor dem Geräteschlüssel steht **echtes Touch ID**. Abläufe, Oberfläche und Backend bleiben,
wie sie sind.

**Warum das wichtig ist.** Heute ist das „Gerät“ ein Browser. Beide Schlüssel sind
WebCrypto-Schlüssel in IndexedDB, und die Abfrage von PIN oder Biometrie wird nur gespielt. Für
den Produktivbetrieb ist das die bekannte Lücke ([09-dpop.md](../09-dpop.md), „Wie sicher die
Schlüssel im Browser sind“; [14-stand-und-weg-zur-produktion.md](../14-stand-und-weg-zur-produktion.md)
Abschnitt 6, Schritt 7 „Die App als native App“). Eine Hülle schließt diese Lücke für die Demo mit
wenig Aufwand. Sie zeigt außerdem, wo genau eine echte App andocken müsste.

**Stand: Konzept, nicht umgesetzt** (Stand 2026-10-11).

---

## 1) Ausgangslage

- **Oberfläche:** `frontend/src/entries/app/AppChannelApp.tsx` im Handy-Rahmen
  `frontend/src/components/PhoneFrame.tsx`. Welcher Schritt als Nächstes kommt, entscheidet das
  Backend über `next`. Die App folgt nur.
- **DPoP-Schlüssel des Kanals:** `frontend/src/dpop.ts`. ECDSA P-256, `extractable=false`, in der
  IndexedDB `identity-demo`. `createDpopProof` baut den JWT und signiert ihn mit WebCrypto.
- **Schlüssel des Verfahrens `device`:** `frontend/src/deviceKey.ts`. Ebenfalls P-256, aber in einer
  eigenen IndexedDB `device-key-demo`. `createDeviceProof` baut den `device-proof+jwt` mit dem
  Claim `userVerification`. Das Backend prüft ihn in `DeviceProofValidator`.
- **Biometrie:** `frontend/src/tools/device/DeviceAccessGate.tsx` simuliert die Systemabfrage mit
  zwei Knöpfen („Mit Biometrie bestätigen“ und „Stattdessen PIN verwenden“).
- **KOBIL:** Das Entsperrgeheimnis liegt im `localStorage` (`frontend/src/kobilUnlockSecret.ts`).

Die Stelle, an der man ansetzen muss, ist klein. Beide Schlüssel werden nur an je einer Stelle
benutzt: beim Signieren der Zeichenkette `header.payload`. Alles andere ist gewöhnliches JSON und
base64url.

## 2) Rahmen: was ohne Xcode geht

Auf dem Entwicklungsrechner sind nur die Command Line Tools installiert (Swift 6.4, macOS-SDK).
Xcode ist nicht installiert.

- **macOS geht.** `swift build` (SwiftPM) übersetzt AppKit-, `WebKit`-, `CryptoKit`- und
  `LocalAuthentication`-Code. Das `.app`-Bundle setzt ein Skript zusammen (`Contents/MacOS`,
  `Contents/Info.plist`) und signiert es ad hoc mit `codesign -s -`. Die App läuft dann lokal.
- **iOS geht nicht.** Das iOS-SDK und der Simulator kommen nur mit Xcode. Auf ein echtes iPhone
  kommt eine App außerdem nur mit Provisioning-Profil.

## 3) Die Idee: eine hybride macOS-Hülle

```
┌──────────── IdentityDemo.app (Swift, ohne Xcode) ─────────────┐
│  WKWebView ── lädt ──▶ http://localhost:…/app/  (unverändert)  │
│      │  window.webkit.messageHandlers.nativeKeys               │
│      ▼                                                         │
│  NativeKeys:  getPublicJwk(slot) · sign(slot, input) · reset   │
│      │                                                         │
│      ▼                                                         │
│  Secure Enclave (CryptoKit)    Touch ID (LocalAuthentication)  │
└────────────────────────────────────────────────────────────────┘
```

- **Fenster.** Ein Fenster im Handyformat mit einer `WKWebView`. Die Adresse kommt aus einem
  Argument oder einer Umgebungsvariable. Standard ist die lokale Adresse aus
  [13-ausfuehren.md](../13-ausfuehren.md) Abschnitt 3.
- **Brücke.** Ein `WKScriptMessageHandlerWithReply` namens `nativeKeys` mit drei Aktionen:
  - `getPublicJwk(slot)` legt den Schlüssel an oder lädt ihn. Es liefert `{kty, crv, x, y}`.
  - `sign(slot, input)` signiert die Zeichenkette `header.payload`. Es liefert die Signatur als
    **raw r‖s (64 Byte)**, base64url. Das ist das JWS-Format für ES256
    (`signature.rawRepresentation`, nicht DER).
  - `reset(slot)` löscht den Schlüssel.
- **Zwei Slots, wie heute zwei Datenbanken:**
  - `dpop`: ein Secure-Enclave-Schlüssel ohne Nutzerabfrage. Er signiert jede Anfrage.
  - `device`: ein Secure-Enclave-Schlüssel mit `SecAccessControl` (`.privateKeyUsage` und
    `.biometryCurrentSet`). Jedes Signieren löst die echte Touch-ID-Abfrage des Systems aus.
- **Ablage.** `SecureEnclave.P256.Signing.PrivateKey` liefert mit `dataRepresentation` nur einen
  verschlüsselten Verweis. Der Schlüssel selbst verlässt die Secure Enclave nie. Der Verweis liegt
  als Datei unter `~/Library/Application Support/identity-demo/<slot>.key`. Die Keychain wird
  bewusst nicht benutzt, denn sie verlangt Entitlements und damit eine echte Signatur.
- **Bau.** Ein Verzeichnis `native-macos/` mit `Package.swift`, zwei Swift-Dateien, `Info.plist`
  (mit einer ATS-Ausnahme für `localhost`) und `build-app.sh`. Grob geschätzt sind das 200 bis
  300 Zeilen Swift.

## 4) Was sich im Frontend ändern würde

- Eine kleine Abstraktion für den Schlüssel, etwa
  `ProofKey { publicJwk; sign(input): Promise<Uint8Array> }`. `createDpopProof` und
  `createDeviceProof` bauen den JWT wie bisher und rufen nur noch `sign`.
- Eine Datei `nativeKeys.ts` erkennt die Hülle (`window.webkit?.messageHandlers?.nativeKeys`).
  Ist sie da, kommen die Schlüssel aus der Brücke. Sonst gilt WebCrypto wie heute. Der Browser
  bleibt also voll lauffähig, und Vitest und Playwright laufen unverändert.
- `DeviceAccessGate` entfällt in der Hülle oder zeigt nur noch einen Hinweis, denn die Abfrage
  kommt vom System. Bricht der Nutzer Touch ID ab, erscheint ein verständlicher Fehler.
- `resetDpopKeyPair()` ruft `reset('dpop')` und rührt `device` nie an. Das ist dieselbe Trennung
  wie heute ([09-dpop.md](../09-dpop.md), „Drei Schlüssel“).
- **Das Backend bleibt unverändert.** Es prüft weiter ES256 gegen den `jwk` im Header. Woher die
  Signatur kommt, sieht es nicht.

## 5) Was realistischer wird und was nicht

| Aspekt | Browser heute | macOS-Hülle |
|---|---|---|
| Wo der private Schlüssel liegt | Daten des Browsers | Secure Enclave (Hardware) |
| PIN/Biometrie | simuliert | echtes Touch ID |
| Gerät | Browserprofil | ein Mac |
| QR-Login | Code eintippen | Code eintippen (keine Kamera) |
| Push, iOS, App Store | nein | nein |
| Verteilung | URL | lokal gebaut, ad hoc signiert, nicht notarisiert |

Ehrlich bleibt: Ein Mac ist kein Smartphone. Die Hülle zeigt die **Sicherheitseigenschaften** einer
nativen App, nicht ihr Formfaktor.

## 6) Alternativen, kurz

- **PWA auf einem echten iPhone.** Die Oberfläche wird über HTTPS erreichbar gemacht und zum
  Home-Bildschirm hinzugefügt. Das kostet nichts und fühlt sich wie ein Handy an, aber die
  Schlüssel bleiben Browser-Schlüssel. Es wäre also nur realistischer in der Haptik, nicht in der
  Sicherheit.
- **Xcode nur als Werkzeug, dazu eine iOS-Hülle.** Dieselbe Brücke mit `WKWebView`, Secure Enclave
  und Face ID, gebaut per `xcodebuild` im Terminal. Dazu kämen die Kamera für den QR-Login und
  später Push. Das ist der natürliche nächste Schritt, wenn die macOS-Hülle trägt. Die Brücke
  wäre schon fertig.
- **Eine vollständig native App** (SwiftUI, eigene Oberflächen für jedes Tool). Am
  realistischsten, aber jedes Verfahren bräuchte eine zweite Oberfläche. Das ist mehr Aufwand, als
  diese Idee will (siehe auch [Verfahren als externe Module](verfahren-als-externe-module.md)).

## 7) Offene Fragen und Risiken

- **Secure Enclave ohne echte Signatur.** Funktioniert `SecureEnclave.P256` mit Ad-hoc-Signatur und
  ohne Entitlements? Erwartet wird ja, weil die Keychain nicht benutzt wird. Ein Mini-Spike klärt
  das, bevor man die Hülle baut: ein CLI, das einen Schlüssel erzeugt, mit Touch ID signiert und
  prüft. Scheitert es, braucht man ein kostenloses Apple-Development-Zertifikat.
- **Intel-Macs** ohne T2-Chip haben keine Secure Enclave. Die Hülle sollte dann klar scheitern und
  nicht still auf einen Software-Schlüssel ausweichen.
- **`userVerification`.** Heute wählt der Nutzer pro Versuch zwischen `biometric` und `pin`. Mit
  `.biometryCurrentSet` gibt es nur `biometric`. Will man die PIN behalten, könnte der Schlüssel
  `.userPresence` verlangen. Die Hülle prüft dann vorher per `LAContext.evaluatePolicy` mit
  Biometrie oder mit Gerätepasswort und reicht diesen Kontext an das Signieren weiter. Welche
  Abfrage stattfand, weiß dann die Hülle und nicht mehr die Seite.
- **KOBIL-Geheimnis.** Es gehört in einer echten App hinter Biometrie. Ein vierter Brückenaufruf
  (`storeSecret` und `loadSecret`, dann doch in der Keychain) wäre der nächste Schritt, braucht
  aber Entitlements.
- **Herkunft der Seite.** Die Brücke steht jeder Seite in der `WKWebView` offen. Die Hülle sollte
  deshalb nur ihre konfigurierte Adresse laden und Navigation woandershin blockieren.
