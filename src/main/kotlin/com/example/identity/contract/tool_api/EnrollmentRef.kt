package com.example.identity.contract.tool_api

/**
 * An opaque reference to a credential enrollment record owned by some module.
 *
 * @property type identifies which kind of enrollment this is (e.g. `"auth_sms.enrollment"`).
 * @property id the enrollment's id within its own store, as a string.
 */
data class EnrollmentRef(val type: String, val id: String)
