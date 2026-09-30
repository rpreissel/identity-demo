package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.validateValue
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.texts.Text

/**
 * The result of one tool step. This is the only thing a tool hands back across the module
 * boundary - any internal state a tool uses to structure itself stays inside its own module.
 */
sealed interface ToolOutcome {

    /** The tool is still running and expects another call. */
    data class InProgress(
        /**
         * What the client must do next, in the tool's own step vocabulary (e.g. `"code"`).
         * Surfaces as `next.step`; the first one of a fresh session equals [ToolDescriptor.startStep].
         */
        val nextStep: String,
        /**
         * What this step needs the client to see, as a declared shape ([StepData]). The
         * orchestrator passes it through unread. A declared type instead of a free map puts the
         * shape into the contract; see [StepDataTypes].
         */
        val stepData: StepData? = null,

        /**
         * Demo-only values this step wants to show (a plaintext TAN, a prefilled address). Not part
         * of the production contract; only `DemoDisclosure` decides whether it reaches a client.
         */
        val demo: Map<String, Any?>? = null
    ) : ToolOutcome

    /**
     * The attempt failed; [reason] is a message for the client. Whom the attempt was against
     * decides which brute-force counter is charged, and for lookup and ident tools only the tool
     * knows. So each variant names its subject as a required field; "nobody" is an explicit `null`.
     * The allowed variant follows from [ToolDescriptor.role] ([fits]).
     */
    sealed interface Failed : ToolOutcome {
        val reason: Text

        /** A [KNOWN_ACCOUNT_AUTH][ToolRole.KNOWN_ACCOUNT_AUTH] attempt - against the account the channel already knows. */
        data class KnownAccountAuth(override val reason: Text) : Failed

        /**
         * A [ACCOUNT_LOOKUP_AUTH][ToolRole.ACCOUNT_LOOKUP_AUTH] attempt - against whom the input resolved ([Attempted]),
         * `null` if it resolved nobody.
         */
        data class AccountLookupAuth(override val reason: Text, val attempted: Attempted?) : Failed

        /**
         * An [IDENTIFICATION][ToolRole.IDENTIFICATION] or [CORRELATION][ToolRole.CORRELATION]
         * attempt - against the person the input resolved, `null` if it resolved none.
         */
        data class Identification(override val reason: Text, val attemptedPersonId: PartnerNumber?) : Failed

        /**
         * An [ENROLLMENT][ToolRole.ENROLLMENT], [ATTESTATION][ToolRole.ATTESTATION] or
         * [PEER_APPROVAL][ToolRole.PEER_APPROVAL] attempt. No secret of an existing account was
         * guessed, so no counter applies; the ToolSession's own limits bound it.
         */
        data class NothingGuessed(override val reason: Text) : Failed

        fun fits(role: ToolRole): Boolean = when (this) {
            is KnownAccountAuth -> role == ToolRole.KNOWN_ACCOUNT_AUTH
            is AccountLookupAuth -> role == ToolRole.ACCOUNT_LOOKUP_AUTH
            is Identification -> role == ToolRole.IDENTIFICATION || role == ToolRole.CORRELATION
            is NothingGuessed -> role == ToolRole.ENROLLMENT || role == ToolRole.ATTESTATION || role == ToolRole.PEER_APPROVAL
        }
    }

    /**
     * The tool finished successfully. The concrete variant must match the tool's
     * [ToolDescriptor.role] ([fits]) and determines what the caller does with the result.
     */
    sealed interface Completed : ToolOutcome {
        /** The amr value(s) this run proved. */
        val amr: List<String>
        /** The level this run itself achieved, if the tool can determine it. */
        val achievedAcr: AcrLevel?
        /** The factor kinds actually proven this run; a subset of [ToolDescriptor.factorTypes]. */
        val factorTypes: Set<FactorType>

        fun fits(role: ToolRole): Boolean = when (this) {
            is Identified -> role == ToolRole.IDENTIFICATION || role == ToolRole.CORRELATION
            is Enrolled -> role == ToolRole.ENROLLMENT
            is Attested -> role == ToolRole.ATTESTATION
            is Authenticated -> role == ToolRole.KNOWN_ACCOUNT_AUTH || role == ToolRole.ACCOUNT_LOOKUP_AUTH
            is Approved -> role == ToolRole.PEER_APPROVAL
        }

        /**
         * An identifying tool established who the subject is. The person reference is a
         * `PERSON_ID` claim, at most one. It may be missing: `ident-eid` reads no person
         * reference, and the central resolution decides between an existing account and an
         * prospect (ADR-10).
         */
        data class Identified(
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** The attributes this run asserted; covered by the descriptor's [ToolDescriptor.claims]. */
            val claims: List<Claim> = emptyList(),
            /** Method-specific verification evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed {
            /** The `PERSON_ID` claim's value, parsed - `null` when this run resolved nobody. */
            val personId: PartnerNumber?
                get() = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }?.value?.let(PartnerNumber::parse)

            init {
                val personIdClaims = claims.count { it.attributeType == AttributeType.PERSON_ID }
                check(personIdClaims <= 1) {
                    "Completed.Identified allows at most one PERSON_ID claim, got $personIdClaims"
                }
                claims.forEach { it.validateValue() }
            }
        }

        /** An [ENROLLMENT][ToolRole.ENROLLMENT] tool created a durable credential. */
        data class Enrolled(
            /**
             * The credential row the tool's module just wrote. The only handle outside that module,
             * used to authenticate against it and to delete it ([com.example.identity.contract.tool_api.credentials.EnrollmentCleanup]).
             */
            val enrollmentRef: EnrollmentRef,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** The attributes this enrollment asserted; covered by [ToolDescriptor.claims]. */
            val claims: List<Claim> = emptyList(),
            /**
             * What the owning module reads back about this instance later, through its
             * [ToolDescriptor.keyBinding] or [ToolDescriptor.instanceDisclosure]. Deleted with the
             * method, so it is no audit evidence (ADR-39).
             */
            val instanceDetails: Map<String, Any?> = emptyMap(),
            /** User-chosen display name, meaningful only for multi-instance methods. */
            val label: String? = null
        ) : Completed

        /**
         * An [ATTESTATION][ToolRole.ATTESTATION] tool proved the subject controls an attribute:
         * claims, but no credential and no identity resolution. [amr] is always empty, because a
         * confirmed address is no authentication proof and must not raise the channel's ACR. It
         * reports no level of its own: the anchor is written under the session's level.
         */
        data class Attested(
            /** What the subject just proved control of. Never empty. */
            val claims: List<Claim>,
        ) : Completed {
            override val amr: List<String> = emptyList()
            override val achievedAcr: AcrLevel? = null
            override val factorTypes: Set<FactorType> = emptySet()

            init {
                check(claims.isNotEmpty()) { "Completed.Attested without a claim attests nothing" }
                claims.forEach { it.validateValue() }
            }
        }

        /** A [KNOWN_ACCOUNT_AUTH][ToolRole.KNOWN_ACCOUNT_AUTH] or [ACCOUNT_LOOKUP_AUTH][ToolRole.ACCOUNT_LOOKUP_AUTH] tool succeeded. */
        data class Authenticated(
            override val amr: List<String>,
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * Set only by a [ACCOUNT_LOOKUP_AUTH][ToolRole.ACCOUNT_LOOKUP_AUTH] tool, which resolves whom it proved
             * itself: an account, or the invitation of a one-time password ([Subject]).
             */
            val subject: Subject? = null,
        ) : Completed

        /**
         * A [PEER_APPROVAL][ToolRole.PEER_APPROVAL] tool approved a pending request from another
         * channel. Declining is an ordinary [Failed].
         */
        data class Approved(
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
        ) : Completed
    }
}
