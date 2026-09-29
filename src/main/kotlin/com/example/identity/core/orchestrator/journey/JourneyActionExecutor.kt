package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.proofLevel
import com.example.identity.core.orchestrator.domain.journey.linksDeviceImplicitly
import com.example.identity.core.orchestrator.domain.journey.levelToWriteUnder
import com.example.identity.core.orchestrator.domain.journey.credentialsLivingOn
import com.example.identity.core.orchestrator.domain.journey.checkCorrelation
import com.example.identity.core.orchestrator.domain.journey.checkAttestationMove
import com.example.identity.core.orchestrator.domain.journey.accountOfProof
import com.example.identity.core.orchestrator.domain.journey.MethodDependencies
import com.example.identity.core.orchestrator.domain.journey.IdentificationTarget
import com.example.identity.core.orchestrator.domain.journey.AccountMerge
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.RetractionAnchor
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.AuthContextService
import com.example.identity.core.orchestrator.session.AuthEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.toCoreEvidence
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.directory.IdentityResolver
import com.example.identity.contract.tool_api.directory.Resolution
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.assertClaimsCovered
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * The acting phase of a transition: executes the [Action] a [Transition.Perform] carries.
 * [JourneyService] decides and routes; this class writes accounts, claims, credentials, device
 * links and revocations. Every side effect of a journey is reachable from this one file.
 * It never calls back into the machine, so [JourneyService.applyTransition] stays the only recursion.
 */
@Component
@Transactional
class JourneyActionExecutor(
    private val journeyRepository: AuthJourneyRepository,
    private val accountService: AccountService,
    private val identityResolver: IdentityResolver,
    private val authContextService: AuthContextService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val accountDeletionService: AccountDeletionService,
    private val toolRegistry: ToolHandlerRegistry,
    private val authPolicy: AuthPolicy,
    private val journeyRecorder: JourneyRecorder,
    private val contextFactory: JourneyContextFactory
) {
    /**
     * Whether [personId] is the person already attested for the account in hand. A CORRELATION
     * tool asks this before it reports; [performRecordIdentification] enforces the same rule
     * afterwards. It lives here because only this class may consult [IdentityResolver].
     */
    fun matchesAttestedIdentity(journey: AuthJourney, channel: ChannelSession, personId: String): Boolean {
        val inHand = channel.accountId ?: return false
        return identityResolver.attestedIdentityMatches(inHand, personId)
    }

    /**
     * Executes [action]. Returns a demo-only notice for the response's `demo` block, or `null`.
     * Only [Action.AdoptCredential] returns one ([performAdoptCredential]).
     */
    fun perform(journey: AuthJourney, channel: ChannelSession, action: Action): Map<String, Any?>? {
        var demoNotice: Map<String, Any?>? = null
        when (action) {
            is Action.RecordIdentification -> performRecordIdentification(journey, channel, action)
            is Action.AdoptAttestation -> performAdoptAttestation(journey, channel, action)
            is Action.AdoptCredential -> demoNotice = performAdoptCredential(journey, channel, action)
            is Action.AcceptProof -> performAcceptProof(journey, channel, action)
            is Action.ApplyRestoredEvidence ->
                journeyRecorder.mergeEvidence(journey, channel, action.source, action.methods)
            // The tool already wrote its own effect (QrLoginRequest). achievedAcr/amr are empty, so
            // this bookkeeping never changes the channel's evidence.
            is Action.RecordApproval -> journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
            is Action.RevokeAuthMethod -> removeMethod(journey, channel, action.methodInstanceId)
            is Action.RetractAttribute -> performRetractAttribute(journey, channel, action.attributeType)
            is Action.LinkDevice -> performLinkDevice(journey, channel)
            is Action.DeleteAccount -> performDeleteAccount(journey, channel)
        }
        return demoNotice
    }

    /**
     * Central identity resolution (docs/02-domaenenmodell.md #6): the account module owns the
     * matching policy, this class the consequences. Whether an account is already in hand is read
     * from [journey]/[channel] every time, not chosen by a strategy. So no strategy or tool order
     * can skip the merge-safety check in [accountOf].
     */
    private fun performRecordIdentification(journey: AuthJourney, channel: ChannelSession, action: Action.RecordIdentification) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val inHand = channel.accountId
        // A correlation step proves nothing about the subject on its own (ADR-18): see checkCorrelation.
        if (action.tool.role == MethodRole.CORRELATION) {
            val correlatingAccount = checkNotNull(inHand) { "Correlation without a known account under ${journey.intent}" }
            checkCorrelation(loadAccount(correlatingAccount), action.tool.toolId, action.outcome.personId) { personId ->
                identityResolver.attestedIdentityMatches(correlatingAccount, personId)
            }
        }
        val resolution = identityResolver.resolve(action.outcome.claims.toSet())
        val accountId = when (resolution) {
            // With an account in hand this always goes through [accountOf], never a bespoke
            // comparison. That gate refuses a silent merge of two credentialed accounts.
            is Resolution.ExistingAccount ->
                if (inHand == null) resolution.accountId else accountOf(journey, channel, inHand, resolution.accountId)
            // Nothing resolved, the attested subject has no account yet: IdentificationTarget decides.
            Resolution.Unresolved -> {
                val inHandAccount = inHand?.let { accountService.findAccount(it) }
                val target = IdentificationTarget.forUnresolved(inHandAccount) {
                    identityResolver.attestationFits(checkNotNull(inHandAccount).accountId, action.outcome.claims.toSet())
                }
                when (target) {
                    IdentificationTarget.NewAccount -> accountService.createUnidentifiedAccount().accountId
                    is IdentificationTarget.AccountInHand -> target.accountId
                }
            }
        }
        bindAccount(journey, channel, accountId)
        // An identification's achieved level is what this session proved about the identity.
        // AnchorRule.acrFloor prices the PERSON_ID anchor against it.
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = action.outcome.achievedAcr ?: AcrLevel.NONE)
        journeyRecorder.recordIdentification(journey, channel, action.tool, action.outcome)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    /** Which account a confirmed identification writes to, see [AccountMerge] (ADR-20). */
    private fun accountOf(journey: AuthJourney, channel: ChannelSession, inHand: Long, resolved: Long?): Long {
        if (resolved == null || resolved == inHand) return inHand
        return when (val merge = AccountMerge.decide(loadAccount(inHand)) { loadAccount(resolved) }) {
            is AccountMerge.MoveInto -> {
                rebindAccount(journey, channel, from = merge.from, to = merge.into)
                accountService.absorbProvisionalAccount(merge.from, merge.into)
                merge.into
            }
            is AccountMerge.AbsorbResolved -> {
                accountService.absorbProvisionalAccount(merge.resolved, merge.into)
                merge.into
            }
        }
    }

    private fun loadAccount(accountId: Long): AccountProfile =
        accountService.findAccount(accountId) ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=$accountId")

    /**
     * Moves a running channel from its provisional account to the resolved one (ADR-20), before
     * the old account is absorbed and deleted. The evidence trail moves but is not reset: what this
     * session proved still counts. The device link moves too, since it outlives the journey and
     * would otherwise hand a deleted account id to the next FAST_ACCESS run.
     */
    private fun rebindAccount(journey: AuthJourney, channel: ChannelSession, from: Long, to: Long) {
        journey.accountId = to
        channel.subject = Subject.Account(to)
        channel.authEvidenceId?.let { authEvidenceService.rebindToAccount(it, to) }
        if (channel.channel == ChannelType.APP) {
            channel.bindingKeyRef?.let { bindingKeyRef ->
                if (sessionManagementService.findLinkedAccountId(bindingKeyRef) == from) {
                    sessionManagementService.linkDeviceToAccount(bindingKeyRef, to)
                }
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }

    /**
     * An attested attribute (ADR-17): the claims land in the account's log and consolidate their
     * anchor. No method instance, so confirming an address never makes email a login method. No
     * device binding and no `amr`: an attestation says how the account is reachable, not that
     * someone just authenticated.
     */
    private fun performAdoptAttestation(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptAttestation) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        // Created lazily, as in performAdoptCredential: under REGISTER the address may be the
        // first step. An abandoned channel leaves no orphan (deleteIfAbandonedUnidentified).
        val inHand = channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Attested without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        val accountId = accountOfAttestation(journey, channel, inHand, action, coreEvidence)
        // The same capped figure an enrollment gets: an anchor write is priced by what the session
        // proved (AnchorRule.acrFloor), not by the tool's ceiling.
        val provenAcr = levelToWriteUnder(authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId)))
        accountService.recordClaims(accountId, action.outcome.claims, provenAcr = provenAcr)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, effectiveAcr = null)
    }

    /** An attested value that resolves to another account, see [checkAttestationMove]. */
    private fun accountOfAttestation(
        journey: AuthJourney,
        channel: ChannelSession,
        inHand: Long,
        action: Action.AdoptAttestation,
        evidence: AuthEvidence
    ): Long {
        val resolved = (identityResolver.resolve(action.outcome.claims.toSet()) as? Resolution.ExistingAccount)?.accountId
        if (resolved == null || resolved == inHand) return inHand
        checkAttestationMove(evidence, accountService.findAccount(resolved)?.personId) { personId ->
            identityResolver.attestedIdentityMatches(inHand, personId)
        }
        return accountOf(journey, channel, inHand, resolved)
    }

    private fun performAdoptCredential(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptCredential): Map<String, Any?>? {
        val enrolled = action.outcome
        // The descriptor/handler contract holds for every adopting tool.
        assertClaimsCovered(action.tool, enrolled.claims)
        // With no account yet (REGISTER), the first completed enrollment creates one. An abandoned
        // channel leaves no orphan (deleteIfAbandonedUnidentified).
        val accountId = channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Enrolled without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        val label = enrolled.label
        // Generated here: the claims are recorded first and must name the instance that
        // established them, so revoking the method retracts exactly those (ADR-12).
        val methodInstanceId = UUID.randomUUID()
        // The level the session had before this completion (ADR-5, see levelToWriteUnder).
        val enrolledUnderAcr = levelToWriteUnder(authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId)))
        // The claims are recorded under that level, since an anchor write is priced against it.
        // The order is safe: resolveAcr reads only the evidence, not the account.
        accountService.recordClaims(
            accountId,
            enrolled.claims,
            // The same capped figure the credential is stamped with (ADR-5).
            provenAcr = enrolledUnderAcr,
            authMethodId = methodInstanceId
        )
        // Demo transparency for the ADR-5 cap: the new credential is weaker than its catalog entry.
        // Shown now, not only later as a puzzling STEP_UP rejection (docs/04-orchestrierung.md #8).
        val demoNotice =
            if (AcrLevel.rank(enrolledUnderAcr) < AcrLevel.rank(action.tool.maxAcr)) {
                mapOf(
                    "enrolledUnderAcrCapped" to mapOf(
                        "toolId" to action.tool.toolId,
                        "enrolledUnderAcr" to enrolledUnderAcr,
                        "toolMaxAcr" to action.tool.maxAcr
                    )
                )
            } else null
        accountService.addAuthenticationMethod(
            accountId,
            action.tool.method,
            enrolled.enrollmentRef,
            enrolledUnderAcr = enrolledUnderAcr.value,
            details = enrolled.instanceDetails,
            enrolledUnderAmr = evidence.currentAmr,
            channel = channel.channel?.name,
            allowsMultipleInstances = action.tool.allowsMultipleInstances,
            label = label,
            instanceId = methodInstanceId
        )
        linkDeviceIfIntentImplies(journey, channel, accountId)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
        return demoNotice
    }

    private fun performAcceptProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof) {
        val authenticated = action.outcome
        val named = when (val subject = authenticated.subject) {
            is Subject.Invitation -> return acceptInvitation(journey, channel, action, subject.hash)
            is Subject.Account -> subject.id
            null -> null
        }
        val accountId = accountOfProof(action.tool.role, named, channel.accountId)
        bindAccount(journey, channel, accountId)
        linkDeviceIfIntentImplies(journey, channel, accountId)
        // Capped by the instance that was used, see proofLevel (ADR-5).
        val effectiveAcr = proofLevel(
            accountService.findActiveMethods(accountId, action.tool.method), action.tool.keyBinding, channel.bindingKeyRef, authenticated.achievedAcr
        )
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
    }

    /**
     * A one-time password names an invitation instead of an account
     * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): it becomes the channel's subject, and no account
     * is bound, found or created. Only an anonymous channel can take it: an
     * account in hand and an invitation never share a session, and a second invitation does not
     * replace the first.
     */
    private fun acceptInvitation(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof, invitation: String) {
        // Web only for now: the App's journeys and tokens know no subject other than an account.
        check(channel.channel == ChannelType.KEYCLOAK) { "A one-time password signs in on the Web channel only" }
        check(action.tool.role == MethodRole.LOOKUP_AUTH) { "Only a lookup tool may name an invitation" }
        check(channel.accountId == null && channel.invitation == null && channel.authEvidenceId == null) {
            "A process access needs a channel without a subject"
        }
        channel.subject = Subject.Invitation(invitation)
        channel.authEvidenceId = authEvidenceService.createForInvitation(invitation).authEvidenceId
        sessionManagementService.updateChannelSession(channel)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    /**
     * The device link that follows from success, unlike the one the user asks for
     * ([performLinkDevice]). It is a property of the intent ([AuthIntent.bindsDeviceImplicitly]).
     */
    private fun linkDeviceIfIntentImplies(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        val intent = journey.requireIntent()
        val linkedTo = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) }
        if (linksDeviceImplicitly(intent, linkedTo, accountId)) linkDeviceTo(channel, accountId)
    }

    /** Uses the account this session holds now, not one a strategy stored earlier ([Action.LinkDevice]). */
    private fun performLinkDevice(journey: AuthJourney, channel: ChannelSession) {
        val accountId = checkNotNull(channel.accountId) { "LinkDevice without a known account" }
        linkDeviceTo(channel, accountId)
    }

    /**
     * The one way a device becomes linked, implicit or explicit, so the revocation below cannot be
     * skipped. A device linked elsewhere means a user-confirmed rebind; the implicit route refuses
     * that case. KEYCLOAK has no device (docs/02-domaenenmodell.md Abschnitt 1) and gets no link.
     */
    private fun linkDeviceTo(channel: ChannelSession, accountId: Long) {
        if (channel.channel != ChannelType.APP) return
        val bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" }
        val previousAccountId = sessionManagementService.findLinkedAccountId(bindingKeyRef)
        sessionManagementService.linkDeviceToAccount(bindingKeyRef, accountId)
        // A device is bound to one account at a time. After a rebind the previous account's
        // credentials on this key must stop working (docs/09-dpop.md), so they are revoked.
        if (previousAccountId != null && previousAccountId != accountId) {
            credentialsLivingOn(accountService.findAccount(previousAccountId), bindingKeyRef, toolRegistry).forEach {
                accountDeletionService.revokeMethod(previousAccountId, it.id)
            }
        }
    }

    /**
     * The account comes only from the freshly derived context, the same one the permission check
     * uses. So no "checked on A, deleted B".
     */
    private fun performDeleteAccount(journey: AuthJourney, channel: ChannelSession) {
        // Re-check against fresh context, not the strategy's state, as for RevokeAuthMethod.
        val freshCtx = contextFactory.contextFor(journey, channel)
        val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        check(authPolicy.isSatisfied(freshCtx.evidence, requiredAcr, account)) {
            "${journey.intent} decided Action.DeleteAccount without satisfying $requiredAcr"
        }
        accountDeletionService.deleteAccount(account.accountId)
    }

    /**
     * The one action a strategy may ask for that is not a tool run. Guarded against self-lockout:
     * rejected if the account could no longer reach its own channel's floor afterwards.
     */
    private fun removeMethod(journey: AuthJourney, channel: ChannelSession, methodInstanceId: String) {
        val accountId = checkNotNull(channel.accountId) { "Remove without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")
        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound(Text("No such active method for this account"), "methodInstanceId=${methodInstanceId}")

        val dependencies = methodDependencies(account)
        val dependents = dependencies.dependentsOf(target)
        val afterRemoval = dependencies.without(listOf(target) + dependents)
        if (authPolicy.reachability(afterRemoval, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            val alsoFalling = dependents.map { it.method }.distinct()
            throw OrchestratorException.invalidState(
                if (alsoFalling.isEmpty()) Text("Deaktivieren von '{method}' wuerde das Mindestniveau dieses Kanals unterschreiten", "method" to target.method)
                else Text("Deaktivieren von '{method}' (zusammen mit {alsoFalling}) wuerde das Mindestniveau dieses Kanals unterschreiten", "method" to target.method, "alsoFalling" to alsoFalling.joinToString(", "))
            )
        }
        // revokeMethod, not deactivate: the owning module's credential row is deleted, the
        // deactivated instance stays for account deletion to walk (docs/09-dpop.md).
        // Dependents first, so no dependent credential outlives what it depends on.
        dependents.forEach { accountDeletionService.revokeMethod(accountId, it.id) }
        accountDeletionService.revokeMethod(accountId, methodInstanceId)
    }

    /**
     * Withdraws one account attribute and everything that requires it (`AccountService.retractAttribute`).
     * Dependents are found by the same fixpoint as for a method revocation. The floor check runs
     * first, so this refuses rather than leaving the channel below its minimum.
     */
    private fun performRetractAttribute(journey: AuthJourney, channel: ChannelSession, attributeType: AttributeType) {
        val accountId = checkNotNull(channel.accountId) { "Retract without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone(Text("Account not found"), "accountId=${accountId}")

        // Refuse instead of a no-op: the cascade below would otherwise revoke methods for an
        // attribute that was never established.
        if (attributeType !in account.establishedClaims) {
            throw OrchestratorException.notFound(Text("'{attributeType}' ist fuer dieses Konto nicht bestaetigt", "attributeType" to attributeType.wireName))
        }

        val dependencies = methodDependencies(account)
        val falling = dependencies.dependentsOfLostClaims(lost = setOf(attributeType), falling = emptyList())
        val afterRetraction = dependencies.without(falling)
        if (authPolicy.reachability(afterRetraction, contextFactory.acrFloorOf(channel)) !is Reachability.Reachable) {
            val alsoFalling = falling.map { it.method }.distinct()
            throw OrchestratorException.invalidState(
                if (alsoFalling.isEmpty()) Text("Zuruecknehmen von '{attributeType}' wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName)
                else Text("Zuruecknehmen von '{attributeType}' (zusammen mit {alsoFalling}) wuerde das Mindestniveau dieses Kanals unterschreiten", "attributeType" to attributeType.wireName, "alsoFalling" to alsoFalling.joinToString(", "))
            )
        }

        falling.forEach { accountDeletionService.revokeMethod(accountId, it.id) }
        accountService.retractAttribute(accountId, attributeType, RetractionAnchor.ACCOUNT_HOLDER, reason = "attribute withdrawn")
    }

    /** What falls with a credential or an attribute ([MethodDependencies]). */
    private fun methodDependencies(account: AccountProfile) =
        MethodDependencies(account, toolRegistry) { accountService.claimedTypesOf(account.accountId, it.id) }

    /**
     * Both channel types get an [com.example.identity.core.orchestrator.session.EvidenceTrail]. Only APP also
     * gets an [com.example.identity.core.orchestrator.session.AuthContext], because only App channels are
     * issued tokens (docs/05-api.md).
     */
    private fun bindAccount(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        journey.accountId = accountId
        channel.subject = Subject.Account(accountId)
        if (channel.authEvidenceId == null) {
            // Fresh login: start a new evidence trail rather than reuse a stale one.
            val evidenceId = checkNotNull(authEvidenceService.createForAccount(accountId).authEvidenceId)
            channel.authEvidenceId = evidenceId
            if (channel.channel == ChannelType.APP) {
                channel.authContextId = authContextService.createForAccount(accountId, evidenceId).authContextId
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }
}
