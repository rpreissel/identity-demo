package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.contract.tool_api.StepData
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema

/**
 * What a QR pairing step shows: the browser the [pairingCode], the app after approving the
 * [confirmationCode], once, in that one response; it is stored only as a hash.
 */
@JsonTypeName("qr-pairing")
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A QR pairing in progress: the pairing code (browser), or the confirmation code to type into the browser (app, once).")
data class QrPairingStep(
    @field:Schema(example = "7F3K92QL")
    val pairingCode: String? = null,
    @field:Schema(example = "482913")
    val confirmationCode: String? = null
) : StepData

/** The browser's PATCH on `auth-qr`/`auth-qr-lookup`: empty while polling, then the confirmation code. */
data class QrConfirmationCodeRequest(
    @field:Schema(example = "482913") val confirmationCode: String? = null
)
