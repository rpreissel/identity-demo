package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ModuleId
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import kotlin.reflect.KClass

/**
 * The one place that turns a tool's working data into JSON and back (ToolSessionData). Encrypting
 * it would happen here too (docs/adr/ADR-052-umschlagverschluesselung-des-claim-logs.md).
 *
 * Reading is tolerant, because during a rolling deploy one version reads what the other wrote: an
 * unknown field is skipped, a missing one takes its Kotlin default. A state therefore gives every
 * field it adds later a default.
 */
@Component
class ToolSessionDataCodec {

    private val mapper = jacksonMapperBuilder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** `<module id>.<class>`, e.g. `auth_sms.AuthSmsToolSession` - the namespace a state lives in. */
    fun typeName(type: KClass<*>): String = "${ModuleId.of(type.java).id}.${type.java.simpleName}"

    fun write(state: Any): String = mapper.writeValueAsString(state)

    fun <S : Any> read(json: String, type: KClass<S>): S = mapper.readValue(json, type.java)
}
