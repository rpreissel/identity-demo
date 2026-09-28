package com.example.identity.contract.tool_api.credentials

import com.example.identity.contract.tool_api.EnrollmentRef


/**
 * Deletes a method module's long-lived credential row on account deletion. `account` must not
 * depend on method modules by name, so it collects all these beans and dispatches by
 * [enrollmentType]. A module without such a table (`auth_email`, see [EMAIL_ANCHOR_ENROLLMENT])
 * needs none.
 */
interface EnrollmentCleanup {
    /** Matches [EnrollmentRef.type] as written by this module's own enrollment handler. */
    val enrollmentType: String

    /** Deletes the credential row [enrollmentRef] points at. Idempotent: a missing row is no error. */
    fun delete(enrollmentRef: EnrollmentRef)
}
