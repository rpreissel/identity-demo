# Idee: Begriffe vereinheitlichen

> **Status: entschieden, nicht umgesetzt** (Issue `DPoP-demo-tcwr`). Jede Umbenennung unten hat der
> Inhaber am 2026-09-30 einzeln bestätigt, nach einer Prüfung der Begriffe auf Konsistenz,
> Verständlichkeit und Fachsprache. Sie ersetzt die fünf offenen Vorschläge, die hier vorher standen.
> Vor dem ersten Release gibt es keine Kompatibilitätsfelder: Ein neuer Name ersetzt den alten auch im
> Vertrag, `api/published/v1.yaml` wird danach neu eingefroren. Ist alles umgesetzt, gehen die
> Begriffe ins [Glossar](../glossar/glossar.md) und ins [englische Register](../glossar/glossar-englisch.md),
> und diese Datei wird gelöscht.

Jede Stufe ist ein eigener Schritt, keine Umbenennung passiert nebenbei. Geänderte Nutzertexte
laufen danach durch `/translate-texts`.

## Stufe 1: nur Doku und Nutzertexte

| # | Heute | Neu | Warum |
|---|---|---|---|
| A2 | **Bestätigte Angabe** (`AccountClaim`) | **Angabe**, mit Stufe *belegt* (Stammdaten, `AUTHORITATIVE`) / *nachgewiesen* (`PROVEN`) / *behauptet* (`SELF_REPORTED`) | Eine Angabe kann behauptet sein, also gerade nicht bestätigt; „Bestätigen“ heißt außerdem schon `ATTESTATION`. Die Stufen entsprechen „bescheinigt/unbescheinigt“ im externen Glossar. |
| A4 | Niveau, Sicherheitsniveau, Sicherheitsstufe, Vertrauensniveau, Stufe | **Sicherheitsniveau**, in der Doku kurz „Niveau“ | Amtlicher Begriff aus eIDAS Art. 8. Die Nutzertexte mit „Sicherheitsstufe“ werden angeglichen, „Vertrauensniveau“ entfällt. |
| A5 | `loa1..3` nur nach NIST erklärt | Glossar: eigene Skala nach NIST 800-63, **nah an den eIDAS-Niveaus niedrig/substanziell/hoch, aber nicht gleich** | „LoA“ ist ein eIDAS-Begriff; ohne den Hinweis liest ein Fachmann eine Gleichsetzung. |
| A6 | KVNR „änderbar“ ohne Begründung | bleibt änderbar, **mit Begründung** in 02 §6 und im Glossar: Es gibt Klärungsfälle, in denen eine KVNR doppelt vergeben wurde | Fachlich ist der unveränderbare Teil der KVNR lebenslang gleich; die Abweichung braucht einen Grund. |
| A7 | **Versicherungsnummer** | **Mitgliedsnummer** („auch Versicherungsnummer genannt“) | „Versicherungsnummer“ meint im Sprachgebrauch meist die Renten- bzw. Sozialversicherungsnummer. Code-Name in Stufe 3. |
| B1 | **Vertrauensanker** (= `ClaimSource`) | **Quelle** | „Anker“ heißt schon der Suchschlüssel eines Kontos (`AccountAnchor`, bleibt). Betrifft auch den Titel von ADR-12. |
| B2 | DPoP-Nachweis, Geräte-Nachweis | **DPoP-Proof**, **Geräte-Proof** | „Nachweis“ bleibt allein für das, was die Sitzung bewiesen hat. |
| B5 | Verfahren, Methode, Zugangsmittel, Authentisierungsmittel | **Anmeldeverfahren**, kurz „Verfahren“ | „Zugangsmittel“ ersetzen; „Methode“ nur als Code-Name, „Authentisierungsmittel“ nur im Abgleich mit dem externen Glossar. |
| D1 | **Intent** und **Ziel** gemischt | **Intent** | Fester Fachbegriff wie Journey; „Ziel“ nur noch für `targetAcr` („Ziel eines Durchlaufs“). |

## Stufe 2: Code, Vertrag unverändert

| # | Heute | Neu |
|---|---|---|
| B3 | `AttemptBudget`, `AttemptBudgets`, `SmsSendBudget`, … (ADR-44) | `RateLimit`, `RateLimits`, `SmsSendLimit`, …; deutsch „Mengenbegrenzung“. Das Versuchsbudget der Journey (`attemptBudget`) bleibt. |
| B4 | `TrustLevel` | `ClaimTrust` (Werte bleiben) |
| C3 | `AuthContext`, Tabelle `auth_context` | `AppTokenSession`, Tabelle `app_token_session` (Migration) |
| C4 | `AuthEvidence` (Domäne), `EvidenceTrail` (Entität), Tabelle `auth_evidence`, `AmrRecord` | `SessionEvidence`, `SessionEvidenceRecord`, Tabelle `session_evidence` (Migration), `MethodEvidenceRecord`; `MethodEvidence` bleibt. Offen, bei der Umsetzung vorzulegen: wie das Feld `factors` heißt. |
| D3 | `identity.policy.elevated-level-max-age` | `identity.policy.loa2-max-age` |
| D4 | `AccountProfile.isProvisional` | `isDisposable`, deutsch „verwerfbar“ (ADR-20) |
| tcwr | `AccountService.createUnidentifiedAccount()` | `createAccountInSetup()` (ADR-46) |

## Stufe 3: Vertrag und Wire, vor dem ersten Release

| # | Heute | Neu |
|---|---|---|
| A3 | `IDENTIFIED_AUTH`, `LOOKUP_AUTH` | `KNOWN_ACCOUNT_AUTH`, `ACCOUNT_LOOKUP_AUTH`; deutsch „Anmeldung eines bekannten Kontos“, „Anmeldung mit Kontosuche“ |
| A7 | `InsuranceNumber`, Anker `INSURANCE_NUMBER` | `MemberNumber`, `MEMBER_NUMBER` (gespeicherte Anker per Migration) |
| B1 | `channel_anchor` (Peer-Auth-Claim), `channelAnchor` | `channel_binding`, `channelBinding`; Erweiterung und Backend zugleich |
| C1 | `MethodRole` | `ToolRole`, deutsch „Tool-Rolle“; das Wire-Feld `role` bleibt |
| C2 | `ChannelType.KEYCLOAK` | `WEB`; Wire-Wert, Frontend, Erweiterung, gespeicherte Zeilen (Migration) |
| C5 | `Subject.Invitation(hash)` | `Subject.Invitation(id)` wie `AuthSubject.id` |
| D2 | `Partnernr`, `findPersonIdByPartnernr`, `Interessent` | `PartnerNumber`, `findPersonIdByPartnerNumber`, `Prospect`; die Doku sagt weiter „Interessent“ |

## Bewusst unverändert

- **Einmalkennwort** (A1): stehender Begriff der Domäne, obwohl es bis zur Frist mehrfach gilt;
  ADR-48 erklärt das.
- **Anker** für `AccountAnchor`, **`AuthMethodView`**, **Kanal**, **Journey**, **Tool**,
  **Tool-Durchlauf**, **Subjekt**, **Einladung**, **Vorgangszugang**, **Freischaltcode**,
  **Geräteverknüpfung**, **Partner/Interessent/Versicherter**, **Peer-Auth**, **RestoreData**.
