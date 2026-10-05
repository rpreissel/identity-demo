# ADR-1: Ein Controller je Tool, kein generischer Dispatcher

**Status:** umgesetzt.

**Kontext**: Ein **Tool** ist ein abgeschlossener Arbeitsschritt, den der Nutzer durchläuft, etwa
„SMS einrichten“ oder „mit Passwort anmelden“ (siehe [Glossar](../glossar/glossar.md)). Der Client
startet ein Tool, setzt es mit seinen Eingaben fort und liest seinen Stand. Jedes Tool erwartet dabei
andere Eingaben. Die Frage ist, wie die Anfragen im Server beim richtigen Tool ankommen: über einen
eigenen Einstiegspunkt (Controller) je Tool oder über einen einzigen, der die Anfragen verteilt.

**Entscheidung**: Jedes Tool bekommt einen eigenen, typisierten `@RestController`. Dazu gehört ein
eigenes Request-DTO, also eine Klasse, die genau die Eingaben dieses Tools beschreibt. Die Endpunkte
`POST/PATCH/GET` eines Tools liegen zusammen in seinem eigenen Controller. Mehr dazu in
[Tool-Architektur](../03-tool-architektur.md) Abschnitt 7 und [Projektrahmen](../08-projektrahmen.md)
A11.

**Erwogene Alternative**: Ein einziger Controller nimmt alle Anfragen an und verteilt sie zur
Laufzeit anhand der `toolId` an das passende Tool. Der Inhalt der Anfrage ist dann eine allgemeine
`Map<String, Any?>`.

**Warum diese**: Ein typisiertes DTO zeigt schon am Controller, was ein Tool tatsächlich erwartet.
Bei einer `Map<String, Any?>` steht das nur noch im Code des Handlers. Außerdem wäre eine Verteilung
anhand der `toolId` zur Laufzeit eine Fehlerquelle, die der Compiler nicht sieht: Fehlt für ein neues
Tool der passende `when`-Zweig, fiele das erst zur Laufzeit auf.

**Kosten**: Es entsteht mehr Code. Statt eines einzigen Controllers gibt es einen je Tool, und alle
sind ähnlich aufgebaut (Starten, Fortsetzen, Lesen).

---
