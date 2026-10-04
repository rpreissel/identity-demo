package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.texts.Text

/**
 * The journey ended as FAILED: the attempt budget is used up, or a strategy aborted it. The ending,
 * the last attempt and its counters must commit, or the journey would take further guesses after
 * the `410` (docs/invarianten.md I-2). So every transactional service a journey interaction passes
 * names it in `noRollbackFor`, like [com.example.identity.core.orchestrator.session.ChannelSessionEndedException].
 */
class JourneyEndedException(val text: Text) : RuntimeException(text.template)
