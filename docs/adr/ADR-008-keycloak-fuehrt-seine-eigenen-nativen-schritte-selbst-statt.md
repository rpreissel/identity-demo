# ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

**Status:** umgesetzt.

> **Nachtrag 2026-09-26:** Die Passwortprüfung läuft weiter über `OrchestratorStorageProvider` und
> `MgmtPasswordController`. Der Provider ist aber seit [ADR-38](ADR-038-keycloak-liest-konten.md)
> eine Nutzer-Federation ohne Import. Das heißt: Keycloak legt keine eigenen Nutzer mit
> `federationLink` mehr an, sondern liest jedes Konto bei Bedarf beim Orchestrator nach. Der Text
> unten ist daran angepasst.

**Kontext**: Auf der Website führt **Keycloak** die Anmeldung. Keycloak ist ein fertiges Produkt, das
Anmeldeseiten zeigt und Tokens ausstellt. Es bringt selbst viele Anmeldeverfahren mit, etwa ein
Passwortformular. Daneben gibt es den **Orchestrator**, den Server dieses Projekts. Er hat eigene
Verfahren, die Keycloak nicht kennt, und berechnet das **Sicherheitsniveau** einer Anmeldung, also
wie sehr ihr vertraut wird. Begriffe erklärt auch das [Glossar](../glossar/glossar.md). Die Frage
ist, wie sich die beiden die Arbeit teilen: Macht Keycloak alles selbst, gibt er alles an den
Orchestrator ab, oder teilen sie sich die Schritte?

**Entscheidung**: Im Web-Kanal führt Keycloak seine Anmeldeabläufe so aus, wie sie in Keycloak selbst
konfiguriert sind. Dazu gehören die Conditional-LoA-Subflows (Teilabläufe, die Keycloak je nach
verlangtem Niveau auswählt) und sein eigenes Passwortformular. Den Orchestrator ruft Keycloak nur für
Schritte auf, die er selbst nicht kann. Dafür gibt es drei Wege:

- **Innerhalb einer Anmeldung mit einer gespeicherten Journey** (`AuthJourney`). Eine Journey ist
  ein geführter Ablauf mit mehreren Schritten. Der Einstieg ist
  [`WEB_SELECT_METHOD`](../journeys/web-select-method.md), bei einer Registrierung `REGISTER`
  (`KeycloakChannelService.entryIntentFor`). Solange eine solche Journey läuft, bestimmt allein der
  Orchestrator ACR und AMR. ACR ist das erreichte Niveau, AMR die Liste der benutzten Verfahren.
  Der Orchestrator fasst dabei die Nachweise mehrerer Tools zusammen (siehe
  [05-api.md](../05-api.md) Abschnitt 3b).
- **Als Required Action mit eigener Journey.** Eine Required Action ist ein Schritt, den Keycloak
  einem angemeldeten Nutzer vorschaltet. Auf diesem Weg läuft das Verwalten der Anmeldeverfahren
  (`MANAGE_AUTH_METHODS`), und zwar über `OrchestratorManageMethodsRequiredAction`.
- **Zustandslos, ohne Kanal und ohne Journey**, nur für die Prüfung des Passworts. Dafür ruft der
  `OrchestratorStorageProvider` den `MgmtPasswordController` im Modul `auth_password` auf. Der
  Provider ist eine Nutzer-Federation ohne Import (siehe [ADR-38](ADR-038-keycloak-liest-konten.md)).
  Hier fasst der Orchestrator keinen Nachweis zusammen. Er trägt nur das Ergebnis am Konto ein (über
  den Port `KeycloakToolCalls`). Das Passwortformular und die Steuerung des Niveaus bleiben bei
  Keycloak.

**Erwogene Alternativen**:

- **Den Orchestrator als externen OIDC-Identity-Provider anbinden.** OIDC ist der Standard, über den
  Anmeldedienste einander Nutzer übergeben. Beim Identity Brokering leitet Keycloak den Browser zu
  einer eigenen Weboberfläche des Orchestrators weiter. Das verletzt die Grundregel „Der Browser
  spricht nie mit dem Orchestrator“ (siehe
  [ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md)).
- **Die ganze Anmeldung an den Orchestrator abgeben.** Keycloak würde dann jedes Formular über einen
  einzigen allgemeinen Authenticator anzeigen. Das ist sauber, verzichtet aber ganz auf die
  eingebauten Fähigkeiten von Keycloak: Passwortanmeldung, OTP/TOTP, WebAuthn/Passkey, Anmeldung über
  soziale Netzwerke und Conditional-LoA. Genau wegen dieser Fähigkeiten ergibt die Anbindung an
  Keycloak Sinn.
- **Jeden Schritt zustandslos aufrufen, ganz ohne Journey.** Dann könnte der Orchestrator mehrere
  eigene Tools in derselben Anmeldung nicht mehr zu einem gemeinsamen Nachweis zusammenfassen. Deshalb
  gibt es den zustandslosen Aufruf nur dort, wo nichts zusammenzufassen ist: bei der Passwortprüfung.

**Begründung**: Keycloak bringt ausgereifte eigene Anmeldeverfahren mit, und die sollen genutzt
werden. Der Orchestrator ergänzt nur, was Keycloak fehlt: die eigenen Verfahren der Demo und das
Zusammenfassen mehrerer Nachweise zu einem Niveau.

**Folgen und Kosten**: Zwei Systeme speichern Zustand, und ihre Sicht kann auseinanderlaufen. Das
Risiko ist begrenzt, weil sich die Zuständigkeiten nicht überschneiden. Keycloak entscheidet, ob ein
Niveau angefragt wird und welches. Der Orchestrator entscheidet, was innerhalb dieses Niveaus
geschieht und wie aus mehreren Nachweisen ein gemeinsamer ACR wird.

**Geschichte**: Anfangs rief Keycloak den Orchestrator nur über `WEB_SELECT_METHOD` auf und prüfte das
Passwort selbst. Später kamen dazu: der Einstieg `REGISTER`, die Required Action für das Verwalten
der Verfahren und die Passwortprüfung über den Orchestrator (Stand 2026-09-23).
