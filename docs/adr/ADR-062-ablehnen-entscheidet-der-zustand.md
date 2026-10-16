# ADR-62: Was Ablehnen bewirkt, steht am Zustand, nicht in der Strategie

**Status:** entschieden und umgesetzt 2026-10-13 (Issue `DPoP-demo-xtyt.2`). Setzt die Forderung aus
[04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 3 um: „Welche Sorte ein Zustand ist, muss
im Code sichtbar sein.“

**Worum es geht.** Wenn der Nutzer ein gestartetes Tool verwirft („Zurück“, „Wechseln“), gibt es
seit jeher zwei Sorten von Antwort: Ein Pflichtzustand bietet alles noch einmal an, ein
Ausweichzustand streicht das Tool und geht erst weiter, wenn nichts mehr übrig ist. Welche Sorte
ein Zustand war, stand aber nicht am Zustand, sondern in der Strategie: 15 Arme für
`JourneyEvent.Abandoned`, verteilt auf zehn Strategien, mit den Helfern `reoffer`, `declineTool`
und zwei Kopien von `mandatory()`. Wer die Sorte eines Zustands wissen wollte, musste die
Strategie lesen.

**Entscheidung.**

- Jeder Angebots-Zustand (`OfferingState`) deklariert seine Sorte: `declinePolicy` ist `MANDATORY`,
  `FALLBACK` oder `OPTIONAL`. `OPTIONAL` ist neu benannt, aber nicht neu: Die Zuordnung zur Person
  in `REGISTER` ließ sich schon immer mit dem ersten Ablehnen überspringen.
- Das Ablehnen beantwortet der Fachkern, nicht die Strategie: `OfferingState.afterAbandoning`
  liefert bei `MANDATORY` das volle Angebot, bei `FALLBACK` das verkleinerte, und `null`, wenn
  nichts mehr übrig oder der Schritt optional ist. `JourneyService` wendet das an, bevor es eine
  Strategie fragt.
- Die Strategie sieht nur noch `JourneyEvent.OfferExhausted(declined)` und nennt den nächsten Weg:
  `Cancel`, eine erneute Identifizierung, die Registrierung oder das Weitermachen ohne Zuordnung.
  `Abandoned` erreichte eine Strategie zunächst noch in einem Zustand ohne Angebot
  (`FastAccessState.PreferredAuth`); seit der auch ein Angebot hält, gibt es das Ereignis nicht mehr.
- Der Journey-Trace zeigt `OfferExhausted` mit allen abgelehnten Tools als eigenes Ereignis.

**Warum.** Die Sorte ist eine Eigenschaft des Angebots, und jetzt steht sie dort. Eine Regel
(`afterAbandoning`) statt 15 Arme; ein Test (`DeclinePolicyTest`) statt eines je Strategie. Eine
Strategie entscheidet nur noch, was nach einem erschöpften Angebot kommt, und genau das ist ihr
fachlicher Anteil.

**Erwogene Alternative.** Nur die beiden `mandatory()`-Kopien zusammenlegen und `declineTool`
behalten. Verworfen: Die Sorte stünde weiter in der Strategie, und die Doku-Forderung bliebe
unerfüllt.

**Preis.** Ein Ereignis mehr in der SPI (`OfferExhausted`). Wer einen Angebots-Zustand anlegt, muss
die Sorte nennen; der Compiler erzwingt es.
