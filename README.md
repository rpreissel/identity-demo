# Identity-Demo: Anmeldung und Identifizierung für App und Website

Diese Demo zeigt, wie sich Versicherte einer Krankenkasse in der App und auf der Website
registrieren, identifizieren und anmelden. Welcher Schritt als Nächstes kommt, entscheidet ein
Orchestrator; App und Website zeigen nur an, was er vorgibt. Jede Anfrage der App ist per DPoP an
den Schlüssel des Geräts gebunden, und auf der Website übernimmt Keycloak die Anmeldung.

Probieren Sie es aus: Nach wenigen Minuten läuft alles auf Ihrem Rechner.

Die Dokumentation lässt sich hier in [docs/](docs/README.md) lesen oder als Website mit Suche:
<https://rpreissel.github.io/identity-demo/>.

## Die Demo in zwei Videos

- **[Erklärvideo](docs/media/erklaervideo.mp4)** (gut fünf Minuten, gesprochen): worum es geht,
  die Konzepte (Intent, Journey, Tool, `next`), die Sicherheitsniveaus, der Aufbau des Codes, der
  Umsetzungsstand und die Vor- und Nachteile. Für Fachexperten und Reviewer, die den Ansatz
  bewerten wollen.
- **[Demo-Video](docs/media/demo.mp4)** (knapp acht Minuten, gesprochen und mit Untertiteln): die
  Aufgaben der Willkommensseite im Browser, mit echtem Keycloak: in der App registrieren, QR-Code-Anmeldung
  und Passwort einrichten, auf der Website mit Passwort anmelden und für die Gesundheitsdaten mit der App
  bestätigen, eine Namensänderung, die die App übernimmt, ein Vorgang mit Einmalkennwort ohne Konto, der
  Journey-Trace und das Löschen des Kontos. Für alle, die die Demo selbst bedienen oder vorführen.

Die Videos liegen in Git LFS. Auf GitHub lassen sie sich direkt abspielen; zum Auschecken braucht es
`git lfs`.

## In fünf Minuten starten

Sie brauchen JDK 21 und Node.js mit npm. Ohne Keycloak genügt ein Befehl:

```bash
./gradlew bootRun
```

Öffnen Sie danach die Willkommensseite unter <http://localhost:8080/>. Von dort führen Kacheln zur
App, zum Personenverzeichnis, zum Briefkasten und zur Admin-Seite (Anmeldung `admin` / `admin`).

Mit Keycloak, also auch mit der Anmeldung auf der Website, brauchen Sie zusätzlich Podman:

```bash
./gradlew build
podman compose up --build
```

Die Willkommensseite liegt wieder unter <http://localhost:8080/>. Keycloak läuft unter
<https://localhost:8543> mit einem selbstsignierten Zertifikat, dem Ihr Browser einmal vertrauen
muss.

## Was Sie ausprobieren können

- **In der App registrieren**: Holen Sie sich im Briefkasten (`/briefkasten/`) einen Freischaltcode
  und registrieren Sie sich damit in der App. Dort stehen auch die SMS- und E-Mail-Codes.
- **Auf der Website mit dem Handy anmelden**: Die Website zeigt einen QR-Code, und Sie bestätigen
  die Anmeldung in der App (dafür braucht es Keycloak).
- **Daten ändern und im Token sehen**: Ändern Sie im Personenverzeichnis einen Namen und schauen
  Sie, wie der neue Name im Token der App ankommt.
- **Zusehen, was passiert**: Auf der Admin-Seite zeigt das Journey-Trace Schritt für Schritt, was
  der Orchestrator entschieden hat.

## Weiterlesen

- [Überblick](docs/01-ueberblick.md): der beste Einstieg in die Konzepte
- [Karte der Dokumentation](docs/README.md): welches Kapitel wofür da ist, mit Lesepfaden
- [Beispiel](docs/11-beispiel-story.md): eine Person von der Registrierung bis zur Löschung
- [Ausführen und bauen](docs/13-ausfuehren.md): Container, Basis-Images, Keycloak-Varianten, Tests
- [AGENTS.md](AGENTS.md): Hinweise für KI-Agenten, die in diesem Repo arbeiten
