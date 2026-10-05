# Journeys

Eine **Journey** ist ein geführter Ablauf mit mehreren Schritten, der einen Nutzer zu seinem Ziel
bringt. Das Ziel heißt im System **Intent**, etwa „schnell anmelden“ oder „Konto löschen“.

Hier gibt es für jeden Intent ein Zustandsdiagramm. Ein Test prüft jedes Diagramm gegen den Code.
Wie die Diagramme zu lesen sind, erklärt [../04-orchestrierung.md](../04-orchestrierung.md),
Abschnitt 3.

- [`FAST_ACCESS`](fast-access.md)
- [`REGISTER`](register.md), dazu das Experiment
  [„Erst Anmeldeverfahren einrichten“](register-enroll-first.md)
- [`LOOKUP_LOGIN`](lookup-login.md)
- [`WEB_SELECT_METHOD`](web-select-method.md)
- [`STEP_UP`](step-up.md)
- [`RE_IDENTIFY`](re-identify.md)
- [`MANAGE_AUTH_METHODS`](manage-auth-methods.md)
- [`CONFIRM_PEER_LOGIN`](confirm-peer-login.md)
- [`LOGOUT`](logout.md)
- [`DELETE_ACCOUNT`](delete-account.md)
- [Lebenszyklus, unabhängig vom Intent](lebenszyklus-unabhaengig-vom-intent.md): Diese Datei
  verweist nur auf [02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 3. Dort steht das
  Diagramm.
