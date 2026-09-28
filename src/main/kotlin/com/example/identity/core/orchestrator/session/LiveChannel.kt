package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.contract.texts.Text

/**
 * A channel that had not ended when it was looked up: the only form in which anyone may start,
 * move or end a journey on it (docs/invarianten.md I-1). The private constructor makes a journey on
 * a dead channel unrepresentable. Like `RunningJourney` it witnesses the lookup, not a live view:
 * the transition may end the channel.
 */
class LiveChannel private constructor(val session: ChannelSession) {

    companion object {
        fun of(session: ChannelSession): LiveChannel? =
            session.takeIf { it.state?.isTerminal != true }?.let(::LiveChannel)

        fun require(session: ChannelSession): LiveChannel =
            of(session) ?: throw OrchestratorException.invalidState(Text("This channel session has ended"), "state=${session.state}")
    }
}
