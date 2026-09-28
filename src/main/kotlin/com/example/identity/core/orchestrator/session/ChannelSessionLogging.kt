package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.journeytrace.LoggedChannel

/**
 * What the journey trace records about this channel. The mapping lives with the entity, since the
 * trace must not depend on the machine it traces (see [LoggedChannel]).
 */
fun ChannelSession.forLog(): LoggedChannel = LoggedChannel(
    channelSessionId = id,
    bindingKeyRef = bindingKeyRef,
    channelType = channel?.name,
    accountId = accountId
)
