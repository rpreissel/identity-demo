package com.example.identity.contract.tool_api.credentials

import com.example.identity.contract.tool_api.EnrollmentRef


/**
 * Verify or replace the password behind an [EnrollmentRef], for callers outside the password
 * tools' own ToolSession, e.g. KOBIL's unlock by password.
 * Uses the same store as the password tools.
 */
interface PasswordCredentialPort {
    /**
     * @param enrollmentRef the account's active password enrollment, or `null` if it has none.
     * Implementations run the same constant-cost check for `null`, so timing reveals nothing.
     */
    fun verify(enrollmentRef: EnrollmentRef?, candidate: String): Boolean

    /** Hashes and stores [password] as a brand-new enrollment, returning its reference. */
    fun setNew(password: String): EnrollmentRef

    companion object {
        /**
         * The method name the password credential is filed under, for callers that resolve the
         * enrollment first (`AccountDirectory.activeEnrollment`).
         */
        const val METHOD = "password"
    }
}
