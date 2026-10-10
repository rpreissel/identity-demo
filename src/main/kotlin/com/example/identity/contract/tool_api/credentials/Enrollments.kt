package com.example.identity.contract.tool_api.credentials

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.tool_api.ids.ToolSessionId
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull

/** The enrollment row [ref] points at, of this module's [type]; anything else is unresolvable (422). */
fun <T : Any> CrudRepository<T, Long>.requireEnrollment(ref: EnrollmentRef, type: String): T {
    if (ref.type != type) throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${ref.type}")
    val id = ref.id.toLongOrNull() ?: throw UnresolvableReferenceException(Text("Ungueltige Enrollment-Referenz"), "id=${ref.id}")
    return findByIdOrNull(id) ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "id=${ref.id}")
}

/**
 * The enrollment row a tool session noted at its start. Gone meanwhile (removed on another
 * channel), it is unresolvable: a right proof for a method that no longer exists proves nothing,
 * and no wrong guess was made either.
 */
fun <T : Any> CrudRepository<T, Long>.requireEnrollment(rowId: String?, toolSessionId: ToolSessionId): T =
    rowId?.toLongOrNull()?.let { findByIdOrNull(it) }
        ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=$toolSessionId")

/**
 * An [EnrollmentCleanup] for a module whose credential is one row keyed by the reference's id. The
 * module provides it as its own bean, a subclass, so `tool_api` stays without beans.
 */
open class RowEnrollmentCleanup(
    final override val enrollmentType: String,
    private val repository: CrudRepository<*, Long>,
) : EnrollmentCleanup {
    override fun delete(enrollmentRef: EnrollmentRef) {
        repository.deleteById(enrollmentRef.id.toLong())
    }
}
