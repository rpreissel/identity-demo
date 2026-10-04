package com.example.identity.contract.tool_api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import java.net.URI

/**
 * The handle a tool controller holds for one request, for its own tool ([ToolController.tool]).
 * A controller method declares it as a parameter, and its type says which of three states it
 * needs; the orchestrator resolves it before the method runs, checked against the caller's key:
 *
 * - [ToolContext]: the session in the path (`{toolSessionId}`), possibly no longer current - for
 *   reading, where a superseded session gets a clean answer instead of a 409 ([ToolJourney.loadContext]).
 * - [AuthorizedToolContext]: the same, verified to be the journey's current tool - for changing it
 *   ([ToolJourney.loadCurrent]).
 * - [ActivationToolContext]: the tool just activated on the channel in the path
 *   (`{channelSessionId}`), its session created by this request ([ToolJourney.beginActivation]).
 *
 * Parameters are resolved in order, so a `@RequestBody` comes first: an unreadable body then
 * activates nothing (enforced by `ApiBoundaryArchitectureTest`).
 */
interface ToolContext {
    /** The toolId this context was obtained for. */
    val toolId: String
    val toolSessionId: ToolSessionId
    /** The caller's resolved binding key (see [BindingKey]), already checked against the channel. */
    val bindingKeyRef: String
    /**
     * The account in hand: the one this channel knows (device link, earlier login, or bound by the
     * running journey), or `null` while nobody is known yet.
     */
    val accountId: AccountId?
}

/**
 * A [ToolContext] that may change the journey; the only kind [ToolJourney.applyOutcome] accepts.
 * [ToolJourney.beginActivation] creates a fresh session, [ToolJourney.loadCurrent] verifies an
 * existing one against the journey's active tool. So a controller cannot apply an outcome for an
 * unverified session.
 */
interface AuthorizedToolContext : ToolContext

/**
 * An [AuthorizedToolContext] whose session this request created. The only kind
 * [ToolJourney.activated] accepts, so only an activation can answer `201` with a `Location`.
 */
interface ActivationToolContext : AuthorizedToolContext

/**
 * The journey as a tool controller sees it: activation, binding checks, transitions and the
 * response envelope. A controller gets a context
 * ([beginActivation], [loadCurrent] or [loadContext]), runs its own logic to a [ToolOutcome], and
 * calls [applyOutcome] (writes) or [buildReadResponse] (reads).
 */
interface ToolJourney {
    /**
     * Activates [toolId] on the channel: creates a new tool session and advances the journey to it.
     *
     * @param bindingKeyRef the caller's resolved DPoP binding key (see [BindingKey]).
     * @throws RuntimeException if the channel or binding is invalid, or the journey does not offer [toolId].
     */
    fun beginActivation(channelSessionId: ChannelSessionId, bindingKeyRef: String, toolId: String): ActivationToolContext

    /**
     * Loads the context of an existing tool session for the read path. Whether it is still the
     * current tool, [isCurrentTool] tells.
     *
     * @throws RuntimeException if the tool session does not exist or the binding key does not
     * match its channel.
     */
    fun loadContext(toolSessionId: ToolSessionId, bindingKeyRef: String, toolId: String): ToolContext

    /**
     * The `Location` header value for a just-created tool resource.
     *
     * @param baseUri the scheme/host/port the client actually reached.
     */
    fun activationLocation(context: ToolContext, baseUri: URI): URI

    /**
     * The write-path counterpart to [loadContext]: loads an existing tool session and verifies it
     * is the one the journey currently authorizes.
     *
     * @throws RuntimeException if it is not the journey's current tool.
     */
    fun loadCurrent(toolSessionId: ToolSessionId, bindingKeyRef: String, toolId: String): AuthorizedToolContext

    /**
     * The credential of [module]'s method this caller may prove: the account's active one, or for a
     * [ToolModule.onePerDevice] method the one living on the caller's key - a credential bound to
     * another device is not reachable. Resolved here, since a handler may not reference `account`
     * (docs/06-ablaeufe.md #3).
     *
     * @throws UnresolvableReferenceException (422) without an account or without such a credential.
     */
    fun requireEnrollment(context: ToolContext, module: ToolModule): EnrollmentRef

    /**
     * [requireEnrollment] without the refusal: `null` when the account in hand has no such
     * credential, or nobody is known yet. Lets an enrollment say that it replaces one.
     */
    fun findEnrollment(context: ToolContext, module: ToolModule): EnrollmentRef?

    /** @return whether [context]'s toolId is still the journey's current tool. */
    fun isCurrentTool(context: ToolContext): Boolean

    /**
     * Abandons the currently activated tool ("Switch"). What happens next is decided by the
     * journey's current state, not by the caller.
     */
    fun abandon(context: AuthorizedToolContext): ChannelResponse

    /**
     * Leaves the currently activated tool without declining it ("Zurück"): the journey shows its
     * selection page again, this tool still among the options.
     */
    fun back(context: AuthorizedToolContext): ChannelResponse

    /** Applies a tool's [outcome] of any kind to the journey and builds the resulting response. */
    fun applyOutcome(context: AuthorizedToolContext, outcome: ToolOutcome): ChannelResponse

    /**
     * Whether [personId] is the person the account in hand had attested (name, first name, date of
     * birth). A CORRELATION tool asks before it reports, so a number of somebody else fails like
     * an unknown one. `false` without an account. The journey repeats the check when binding.
     * Stays here rather than on [IdentityResolver]: only the journey consults that port.
     */
    fun matchesAttestedIdentity(context: AuthorizedToolContext, personId: PartnerNumber): Boolean

    /**
     * Builds the response for a GET call.
     *
     * @param freshOutcome the tool's rebuilt `InProgress` state, or `null` if it is no longer the
     * current tool; the response then shows the journey's current step.
     */
    fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse
}

/**
 * The answer to a tool activation: applies the first [outcome] and returns `201 Created` with the
 * new tool resource as `Location`. The closing step of every tool controller's `activate`.
 */
fun ToolJourney.activated(
    context: ActivationToolContext,
    outcome: ToolOutcome,
    uriBuilder: UriComponentsBuilder,
): ResponseEntity<ChannelResponse> {
    val response = applyOutcome(context, outcome)
    val location = activationLocation(context, uriBuilder.build().toUri())
    return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
}

/**
 * The answer to a GET on a tool session: [read] rebuilds the tool's current step while it is still
 * the journey's current tool; otherwise the response shows where the journey is now.
 */
fun ToolJourney.readResponse(context: ToolContext, read: () -> ToolOutcome): ResponseEntity<ChannelResponse> {
    val outcome = if (isCurrentTool(context)) {
        checkNotNull(read() as? ToolOutcome.InProgress) { "read() must return InProgress while the tool is still current" }
    } else {
        null
    }
    return ResponseEntity.ok(buildReadResponse(context, outcome))
}
