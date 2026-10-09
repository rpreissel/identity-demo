/* tslint:disable */
/* eslint-disable */
/**
 * One active authentication method instance. `id` addresses it for DELETE .../methods/{id} - method name alone isn't unique when a method allows multiple instances (e.g. several active `device` entries, one per physical device). `label` is a user-chosen display name, set only for multi-instance methods; `null` for singleton ones (email/sms/password), which the client labels from `method` itself. `enrolledUnderAcr`/`maxAcr`/`effectiveAcr`/`factorTypes` surface the ADR-5 three-way cap (docs/12-entscheidungen.md): `effectiveAcr` is `min(enrolledUnderAcr, maxAcr)`, the level this method can actually contribute right now, which can be lower than the tool's own declared `maxAcr` if it was enrolled while the session had proven less.
 * @export
 * @interface ActiveMethodView
 */
export interface ActiveMethodView {
    /**
     * Whether the credential can be changed in place (POST .../methods/{id}/changes): a new password, a new number.
     * @type {boolean}
     * @memberof ActiveMethodView
     */
    changeable?: boolean;
    /**
     * min(enrolledUnderAcr, maxAcr) - what this method actually contributes today.
     * @type {string}
     * @memberof ActiveMethodView
     */
    effectiveAcr?: string;
    /**
     * The level the session had already proven at the moment this method was enrolled (ADR-5) - caps effectiveAcr below maxAcr if lower.
     * @type {string}
     * @memberof ActiveMethodView
     */
    enrolledUnderAcr?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof ActiveMethodView
     */
    factorTypes?: Array<ActiveMethodViewFactorTypesEnum>;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    label?: string;
    /**
     * This tool's own declared ceiling - never account/session-specific.
     * @type {string}
     * @memberof ActiveMethodView
     */
    maxAcr?: string;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    method: string;
}


/**
 * @export
 */
export const ActiveMethodViewFactorTypesEnum = {
    KNOWLEDGE: 'KNOWLEDGE',
    POSSESSION: 'POSSESSION',
    INHERENCE: 'INHERENCE'
} as const;
export type ActiveMethodViewFactorTypesEnum = typeof ActiveMethodViewFactorTypesEnum[keyof typeof ActiveMethodViewFactorTypesEnum];

/**
 * An answer to whatever the current step is waiting on instead of a tool run (docs/04-orchestrierung.md #3) - e.g. "accept"/"decline" for the optional device-binding offer of a lookup login. Which values are valid depends on what next.step is currently offering.
 * @export
 * @interface AnswerRequest
 */
export interface AnswerRequest {
    /**
     * 
     * @type {string}
     * @memberof AnswerRequest
     */
    answer: string;
}
/**
 * 
 * @export
 * @interface AuthData
 */
export interface AuthData {
    /**
     * 
     * @type {string}
     * @memberof AuthData
     */
    acr?: string;
    /**
     * The proven methods, each mapped to "orchestrator": every proof is an orchestrator tool's, also one carried over by RestoreData (ADR-58). The map shape stays for compatibility. Informational only - the orchestrator alone resolves the combined acr above.
     * @type {{ [key: string]: string; }}
     * @memberof AuthData
     */
    amr?: { [key: string]: string; };
    /**
     * 
     * @type {AuthSubject}
     * @memberof AuthData
     */
    subject?: AuthSubject;
}
/**
 * 
 * @export
 * @interface AuthEmailLookupPatchRequest
 */
export interface AuthEmailLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthEmailLookupPatchRequest
     */
    code?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthEmailLookupPatchRequest
     */
    email?: string;
}
/**
 * 
 * @export
 * @interface AuthEmailPatchRequest
 */
export interface AuthEmailPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthEmailPatchRequest
     */
    code?: string;
}
/**
 * 
 * @export
 * @interface AuthInvitePatchRequest
 */
export interface AuthInvitePatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthInvitePatchRequest
     */
    code?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthInvitePatchRequest
     */
    kvnr?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthInvitePatchRequest
     */
    partnerNumber?: string;
}
/**
 * 
 * @export
 * @interface AuthKobilPatchRequest
 */
export interface AuthKobilPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthKobilPatchRequest
     */
    otp?: string;
}
/**
 * 
 * @export
 * @interface AuthPasswordLookupPatchRequest
 */
export interface AuthPasswordLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordLookupPatchRequest
     */
    email?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordLookupPatchRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface AuthPasswordPatchRequest
 */
export interface AuthPasswordPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordPatchRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface AuthSmsLookupPatchRequest
 */
export interface AuthSmsLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthSmsLookupPatchRequest
     */
    email?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthSmsLookupPatchRequest
     */
    tan?: string;
}
/**
 * 
 * @export
 * @interface AuthSmsPatchRequest
 */
export interface AuthSmsPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthSmsPatchRequest
     */
    tan?: string;
}
/**
 * 
 * @export
 * @interface AuthSubject
 */
export interface AuthSubject {
    /**
     * 
     * @type {string}
     * @memberof AuthSubject
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof AuthSubject
     */
    type: AuthSubjectTypeEnum;
}


/**
 * @export
 */
export const AuthSubjectTypeEnum = {
    account: 'account',
    invitation: 'invitation'
} as const;
export type AuthSubjectTypeEnum = typeof AuthSubjectTypeEnum[keyof typeof AuthSubjectTypeEnum];

/**
 * 
 * @export
 * @interface BiometricUnlock
 */
export interface BiometricUnlock extends KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof BiometricUnlock
     */
    unlockSecret: string;
}


/**
 * 
 * @export
 * @interface BoundCredentialView
 */
export interface BoundCredentialView {
    /**
     * 
     * @type {string}
     * @memberof BoundCredentialView
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof BoundCredentialView
     */
    reference: string;
}
/**
 * 
 * @export
 * @interface ChannelBlock
 */
export interface ChannelBlock {
    /**
     * All active authentication methods on the account, regardless of whether this session's currentAmr proved them. currentAmr is session evidence (what THIS channel actually proved); activeMethods is the account's full standing method list, unfiltered by device - a lost/stolen device's credential must be removable from any authenticated session, not only from that device itself.
     * @type {Array<ActiveMethodView>}
     * @memberof ChannelBlock
     */
    activeMethods?: Array<ActiveMethodView>;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    channelSessionId: string;
    /**
     * Which facade this channel was opened through - APP (DPoP) or WEB (docs/02-domaenenmodell.md Abschnitt 1). Fixed for the channel's whole lifetime.
     * @type {string}
     * @memberof ChannelBlock
     */
    channelType: string;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    currentAcr?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof ChannelBlock
     */
    currentAmr?: Array<string>;
    /**
     * Whether this session has already proven at least one factor (an identification or a method) - what cancelling the running journey would throw away. Lets a client ask "really discard?" only when there is something to lose. Set on every response, tool responses included; says nothing about which factor.
     * @type {boolean}
     * @memberof ChannelBlock
     */
    hasProvenFactor: boolean;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    state: string;
}
/**
 * requiredAcr is a lower bound only. Always creates a brand-new ChannelSession for this device (docs/02-domaenenmodell.md #3) - DPoP proves the device, never a lookup key for resuming a session. To end a previous session first (logout), call DELETE .../channels/{channelSessionId} before this.
 * @export
 * @interface ChannelCreateRequest
 */
export interface ChannelCreateRequest {
    /**
     * The tools this client supports and has enabled, each as <toolId>@<version> in the one version it speaks (ADR-51, docs/03-tool-architektur.md, availability) - e.g. GET /tools/catalog minus whatever the user turned off locally. Fixed for this channel's whole lifetime; a candidate list never offers a tool outside this set, and activating one directly or in another version fails too. An entry without its version, or one tool in two versions, is rejected.
     * @type {Array<string>}
     * @memberof ChannelCreateRequest
     */
    availableTools: Array<string>;
    /**
     * The entry intent's own name, case-insensitively (AuthIntent.fromRequest) - no separate wire vocabulary. Omitted/fast_access (default): DeviceAccountLink found -> LOGIN, else REGISTRATION. lookup_login: always offers lookup-based login (email + credential), even on a linked device. register: always starts fresh REGISTRATION, even on a linked device (second account). confirm_peer_login: a cold app approves a waiting web login (QR pairing) for the device's linked account.
     * @type {string}
     * @memberof ChannelCreateRequest
     */
    intent?: ChannelCreateRequestIntentEnum;
    /**
     * 
     * @type {string}
     * @memberof ChannelCreateRequest
     */
    requiredAcr?: string;
}


/**
 * @export
 */
export const ChannelCreateRequestIntentEnum = {
    fast_access: 'fast_access',
    register: 'register',
    lookup_login: 'lookup_login',
    confirm_peer_login: 'confirm_peer_login'
} as const;
export type ChannelCreateRequestIntentEnum = typeof ChannelCreateRequestIntentEnum[keyof typeof ChannelCreateRequestIntentEnum];

/**
 * Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md, step-ups).
 * @export
 * @interface ChannelPatchRequest
 */
export interface ChannelPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ChannelPatchRequest
     */
    requiredAcr: string;
}
/**
 * 
 * @export
 * @interface ChannelResponse
 */
export interface ChannelResponse {
    /**
     * WEB channels only (docs/05-api.md Abschnitt 3b) - never present for APP.
     * @type {AuthData}
     * @memberof ChannelResponse
     */
    authData?: AuthData;
    /**
     * 
     * @type {ChannelBlock}
     * @memberof ChannelResponse
     */
    channel: ChannelBlock;
    /**
     * Demo-only correlation IDs, never part of the production contract.
     * @type {DemoInfo}
     * @memberof ChannelResponse
     */
    demo?: DemoInfo;
    /**
     * 
     * @type {Next}
     * @memberof ChannelResponse
     */
    next?: Next;
    /**
     * Whatever the current step needs to render. `kind` names the shape - see StepData.
     * @type {StepData}
     * @memberof ChannelResponse
     */
    stepData?: StepData;
}
/**
 * 
 * @export
 * @interface Confirm
 */
export interface Confirm extends Prompt {
    /**
     * 
     * @type {TextRef}
     * @memberof Confirm
     */
    cancelLabel: TextRef;
    /**
     * 
     * @type {TextRef}
     * @memberof Confirm
     */
    confirmLabel: TextRef;
    /**
     * 
     * @type {boolean}
     * @memberof Confirm
     */
    destructive?: boolean;
}
/**
 * 
 * @export
 * @interface ConfirmEmailPatchRequest
 */
export interface ConfirmEmailPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmEmailPatchRequest
     */
    code?: string;
    /**
     * 
     * @type {string}
     * @memberof ConfirmEmailPatchRequest
     */
    email?: string;
}
/**
 * 
 * @export
 * @interface ConfirmQrLoginActivateRequest
 */
export interface ConfirmQrLoginActivateRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginActivateRequest
     */
    pairingCode?: string;
}
/**
 * 
 * @export
 * @interface ConfirmQrLoginPatchRequest
 */
export interface ConfirmQrLoginPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginPatchRequest
     */
    decision?: string;
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginPatchRequest
     */
    pairingCode?: string;
}
/**
 * The step waits for a yes/no answer; the prompt is authored by the backend.
 * @export
 * @interface ConfirmStep
 */
export interface ConfirmStep {
    /**
     * 
     * @type {string}
     * @memberof ConfirmStep
     */
    kind: ConfirmStepKindEnum;
    /**
     * 
     * @type {Prompt}
     * @memberof ConfirmStep
     */
    prompt: Prompt;
}


/**
 * @export
 */
export const ConfirmStepKindEnum = {
    confirm: 'confirm'
} as const;
export type ConfirmStepKindEnum = typeof ConfirmStepKindEnum[keyof typeof ConfirmStepKindEnum];

/**
 * 
 * @export
 * @interface DemoInfo
 */
export interface DemoInfo {
    [key: string]: any | any;
    /**
     * 
     * @type {number}
     * @memberof DemoInfo
     */
    accountId?: number;
    /**
     * The running journey chain for this channel, outermost first - see [JourneyDebugStep]. Empty once nothing is running.
     * @type {Array<JourneyDebugStep>}
     * @memberof DemoInfo
     */
    journeys?: Array<JourneyDebugStep>;
    /**
     * 
     * @type {string}
     * @memberof DemoInfo
     */
    personId?: string;
    /**
     * Demo-only: who this session belongs to and what it has proven so far - in every response, tool responses included, so a demo view can show it at any step. The production contract keeps these on the channel resource only (ChannelBlock).
     * @type {DemoSession}
     * @memberof DemoInfo
     */
    session?: DemoSession;
}
/**
 * 
 * @export
 * @interface DemoSession
 */
export interface DemoSession {
    /**
     * 
     * @type {string}
     * @memberof DemoSession
     */
    acr?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof DemoSession
     */
    amr?: Array<string>;
    /**
     * Whether the session is signed in (channel state AUTHENTICATED).
     * @type {boolean}
     * @memberof DemoSession
     */
    authenticated: boolean;
    /**
     * "Vorname Name" of the person behind the account, if one is bound.
     * @type {string}
     * @memberof DemoSession
     */
    personName?: string;
}
/**
 * Whether this device's DPoP key is already linked to an account (DeviceAccountLink, docs/02-domaenenmodell.md #1) - a pure read, no channel/journey created. Lets the entry screen show "this device belongs to X" before the user picks how to start.
 * @export
 * @interface DeviceLinkResponse
 */
export interface DeviceLinkResponse {
    /**
     * 
     * @type {number}
     * @memberof DeviceLinkResponse
     */
    accountId?: number;
    /**
     * Demo-only: what else this device is known by - one entry per key-bound credential of the linked account living on THIS key, with the reference its own method discloses (docs/09-dpop.md). The `device` method names its credential key, `kobil` the identifier the provider gave this phone. Absence is meaningful: a client that holds local data for a method no longer listed here is holding something stale.
     * @type {Array<BoundCredentialView>}
     * @memberof DeviceLinkResponse
     */
    boundCredentials?: Array<BoundCredentialView>;
    /**
     * 
     * @type {boolean}
     * @memberof DeviceLinkResponse
     */
    linked: boolean;
}
/**
 * 
 * @export
 * @interface DeviceProofPatchRequest
 */
export interface DeviceProofPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof DeviceProofPatchRequest
     */
    deviceProof?: string;
    /**
     * 
     * @type {string}
     * @memberof DeviceProofPatchRequest
     */
    label?: string;
}
/**
 * 
 * @export
 * @interface EnrollKobilPatchRequest
 */
export interface EnrollKobilPatchRequest {
    /**
     * 
     * @type {boolean}
     * @memberof EnrollKobilPatchRequest
     */
    activated?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof EnrollKobilPatchRequest
     */
    biometricConsent?: boolean;
    /**
     * 
     * @type {string}
     * @memberof EnrollKobilPatchRequest
     */
    label?: string;
}
/**
 * 
 * @export
 * @interface EnrollPasswordPatchRequest
 */
export interface EnrollPasswordPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof EnrollPasswordPatchRequest
     */
    password?: string;
}
/**
 * Waiting for the password to set; `replaces` says the account already has one.
 * @export
 * @interface EnrollPasswordStep
 */
export interface EnrollPasswordStep {
    /**
     * 
     * @type {string}
     * @memberof EnrollPasswordStep
     */
    kind: EnrollPasswordStepKindEnum;
    /**
     * 
     * @type {Array<string>}
     * @memberof EnrollPasswordStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {boolean}
     * @memberof EnrollPasswordStep
     */
    replaces: boolean;
}


/**
 * @export
 */
export const EnrollPasswordStepKindEnum = {
    enroll_password: 'enroll-password'
} as const;
export type EnrollPasswordStepKindEnum = typeof EnrollPasswordStepKindEnum[keyof typeof EnrollPasswordStepKindEnum];

/**
 * 
 * @export
 * @interface EnrollSmsPatchRequest
 */
export interface EnrollSmsPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsPatchRequest
     */
    phoneNumber?: string;
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsPatchRequest
     */
    tan?: string;
}
/**
 * Waiting for the phone number or its TAN; `replaces` says the account already has a number.
 * @export
 * @interface EnrollSmsStep
 */
export interface EnrollSmsStep {
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsStep
     */
    kind: EnrollSmsStepKindEnum;
    /**
     * 
     * @type {Array<string>}
     * @memberof EnrollSmsStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {boolean}
     * @memberof EnrollSmsStep
     */
    replaces: boolean;
}


/**
 * @export
 */
export const EnrollSmsStepKindEnum = {
    enroll_sms: 'enroll-sms'
} as const;
export type EnrollSmsStepKindEnum = typeof EnrollSmsStepKindEnum[keyof typeof EnrollSmsStepKindEnum];

/**
 * 
 * @export
 * @interface EnrollSmsV2PatchRequest
 */
export interface EnrollSmsV2PatchRequest {
    /**
     * Consent to storing the number and sending codes to it. Required with the phone number; without it no code is sent and missingFields names it.
     * @type {boolean}
     * @memberof EnrollSmsV2PatchRequest
     */
    consent?: boolean;
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsV2PatchRequest
     */
    phoneNumber?: string;
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsV2PatchRequest
     */
    tan?: string;
}
/**
 * Every error response has this shape. The HTTP status is fixed per `error` code:
 * 
 * - `BAD_REQUEST`: 400
 * - `UNAUTHORIZED`: 401
 * - `BINDING_MISMATCH`: 403
 * - `NOT_FOUND`: 404
 * - `INVALID_STATE_TRANSITION`: 409
 * - `CONCURRENT_MODIFICATION`: 409
 * - `PROCESS_GONE`: 410
 * - `PROCESS_ABORTED`: 410
 * - `UNRESOLVABLE_REFERENCE`: 422
 * - `ACCOUNT_LOCKED`: 423
 * - `TOO_MANY_REQUESTS`: 429
 * - `INTERNAL_ERROR`: 500
 * 
 * A client must expect a code it does not know and handle it by its HTTP status.
 * @export
 * @interface ErrorResponse
 */
export interface ErrorResponse {
    /**
     * 
     * @type {string}
     * @memberof ErrorResponse
     */
    error: ErrorResponseErrorEnum;
    /**
     * 
     * @type {TextRef}
     * @memberof ErrorResponse
     */
    text: TextRef;
}


/**
 * @export
 */
export const ErrorResponseErrorEnum = {
    BAD_REQUEST: 'BAD_REQUEST',
    UNAUTHORIZED: 'UNAUTHORIZED',
    BINDING_MISMATCH: 'BINDING_MISMATCH',
    NOT_FOUND: 'NOT_FOUND',
    INVALID_STATE_TRANSITION: 'INVALID_STATE_TRANSITION',
    CONCURRENT_MODIFICATION: 'CONCURRENT_MODIFICATION',
    PROCESS_GONE: 'PROCESS_GONE',
    PROCESS_ABORTED: 'PROCESS_ABORTED',
    UNRESOLVABLE_REFERENCE: 'UNRESOLVABLE_REFERENCE',
    ACCOUNT_LOCKED: 'ACCOUNT_LOCKED',
    TOO_MANY_REQUESTS: 'TOO_MANY_REQUESTS',
    INTERNAL_ERROR: 'INTERNAL_ERROR'
} as const;
export type ErrorResponseErrorEnum = typeof ErrorResponseErrorEnum[keyof typeof ErrorResponseErrorEnum];

/**
 * The attempt failed; retries remain.
 * @export
 * @interface FailedAttemptStep
 */
export interface FailedAttemptStep {
    /**
     * 
     * @type {TextRef}
     * @memberof FailedAttemptStep
     */
    error: TextRef;
    /**
     * 
     * @type {string}
     * @memberof FailedAttemptStep
     */
    kind: FailedAttemptStepKindEnum;
}


/**
 * @export
 */
export const FailedAttemptStepKindEnum = {
    failed_attempt: 'failed-attempt'
} as const;
export type FailedAttemptStepKindEnum = typeof FailedAttemptStepKindEnum[keyof typeof FailedAttemptStepKindEnum];

/**
 * 
 * @export
 * @interface IdentEidPatchRequest
 */
export interface IdentEidPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    birthDate?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    familyName?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    givenNames?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    locality?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    pin?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    postalCode?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    restrictedId?: string;
    /**
     * Straße und Hausnummer in einer Zeile, wie die Karte sie liefert
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    streetAddress?: string;
}
/**
 * 
 * @export
 * @interface IdentFscPatchRequest
 */
export interface IdentFscPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    birthDate?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    familyName?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    fsc?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    givenNames?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    kvnr?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    partnerNumber?: string;
}
/**
 * 
 * @export
 * @interface IdentKvnrPatchRequest
 */
export interface IdentKvnrPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentKvnrPatchRequest
     */
    kvnr?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentKvnrPatchRequest
     */
    partnerNumber?: string;
}
/**
 * 
 * @export
 * @interface IdentNectActivateRequest
 */
export interface IdentNectActivateRequest {
    /**
     * Where Nect sends the user back to; without it the app channel's /app/. The web channel names Keycloak's action URL of the running step. Only an address under a configured prefix is accepted (400 otherwise).
     * @type {string}
     * @memberof IdentNectActivateRequest
     */
    returnUri?: string;
}
/**
 * 
 * @export
 * @interface IdentNectPatchRequest
 */
export interface IdentNectPatchRequest {
    /**
     * The case id Nect sent the user back with (?nectCaseId=...); also accepted as nectCaseId.
     * @type {string}
     * @memberof IdentNectPatchRequest
     */
    caseId?: string;
    /**
     * true opens a fresh Nect case instead of reporting one.
     * @type {boolean}
     * @memberof IdentNectPatchRequest
     */
    retry?: boolean;
    /**
     * With retry: where Nect sends the user back to this time, checked like on activation. The web channel names a fresh action URL, because Keycloak's action code is single-use; without it the address from the activation stays.
     * @type {string}
     * @memberof IdentNectPatchRequest
     */
    returnUri?: string;
}
/**
 * 
 * @export
 * @interface JourneyDebugStep
 */
export interface JourneyDebugStep {
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    intent: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    journeyId: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    lifecycle: string;
    /**
     * Demo-only: why this journey's current step looks the way it does - either why its tool became the automatic choice, or why a selection among several is being shown at all. Null whenever the step already explains itself (e.g. a Prompt), never part of the production contract.
     * @type {TextRef}
     * @memberof JourneyDebugStep
     */
    note?: TextRef;
    /**
     * Demo-only: what this journey's current position is for - the orchestrator's reason for being here at all, set for every journey in the chain. Never part of the production contract.
     * @type {TextRef}
     * @memberof JourneyDebugStep
     */
    purpose?: TextRef;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    stateType: string;
}
/**
 * 
 * @export
 * @interface KeycloakAccountView
 */
export interface KeycloakAccountView {
    /**
     * 
     * @type {number}
     * @memberof KeycloakAccountView
     */
    accountId: number;
    /**
     * 
     * @type {{ [key: string]: string; }}
     * @memberof KeycloakAccountView
     */
    attributes: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof KeycloakAccountView
     */
    email?: string;
    /**
     * 
     * @type {boolean}
     * @memberof KeycloakAccountView
     */
    emailVerified: boolean;
    /**
     * 
     * @type {string}
     * @memberof KeycloakAccountView
     */
    firstName: string;
    /**
     * 
     * @type {string}
     * @memberof KeycloakAccountView
     */
    lastName: string;
    /**
     * 
     * @type {string}
     * @memberof KeycloakAccountView
     */
    username: string;
}
/**
 * Upsert body for the Keycloak facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3). All fields are optional. subject is whom Keycloak already knows (sub vorhanden) - an account binds the channel immediately, once, never overwritten by a later call. targetAcr is Keycloak's requested LoA level, already translated into an orchestrator ACR string, and only raises the channel's floor, never lowers it. Keycloak proves nothing itself: every sign-in step is an orchestrator tool (ADR-58).
 * @export
 * @interface KeycloakChannelUpsertRequest
 */
export interface KeycloakChannelUpsertRequest {
    /**
     * The Web channel's own declaration of which toolIds its Keycloak theme can render (one com.example.identity.kcext.webtool.WebToolRenderer factory per toolId, registered via META-INF/services) - the Keycloak facade's counterpart to the App channel's own availableTools (POST /channels). Only read on this channel's first call (a later upsert resumes the already-persisted set); a channel-anonymous caller that omits this gets none of the orchestrator's tools, never all of them.
     * @type {Array<string>}
     * @memberof KeycloakChannelUpsertRequest
     */
    availableTools?: Array<string>;
    /**
     * Only read on this channel's first call, same restriction as availableTools - the Keycloak facade's own, deliberately narrow counterpart to the App facade's `intent` request parameter (docs/05-api.md #"POST /app/channels: intent-Parameter"). Omitted (or null) means web_select_method, the existing login/step-up behaviour. Only web_select_method and register are accepted here - unlike the App facade, not every AuthIntent.isEntryIntent value: fast_access/lookup_login assume an APP-shaped channel this facade never has.
     * @type {string}
     * @memberof KeycloakChannelUpsertRequest
     */
    intent?: string;
    /**
     * Required whenever restoreData is present, ignored otherwise. Keycloak's own, durable UserSessionModel id - deliberately NOT read off the peer-auth assertion (the assertion's Keycloak binding is always THIS flow run's own channelSessionId, docs/02-domaenenmodell.md Abschnitt 1, so it can't verify a token minted for a DIFFERENT, earlier flow run's channel). Must match what GET .../restore-data was called with to produce this exact restoreData token.
     * @type {string}
     * @memberof KeycloakChannelUpsertRequest
     */
    kcSessionId?: string;
    /**
     * A signed RestoreData token this same UserSession's channel returned earlier via GET .../restore-data, resubmitted verbatim (docs/05-api.md, section 3) - the bulk, one-shot way to seed a brand-new channel with what a PRIOR, unrelated flow run already established. Opaque to every caller but the orchestrator itself - see RestoreDataCodec.
     * @type {string}
     * @memberof KeycloakChannelUpsertRequest
     */
    restoreData?: string;
    /**
     * Whom Keycloak knows this flow run belongs to: an account or an invitation (ADR-48), the same shape as authData.subject in the answer. A channel already bound to another subject refuses it (409); an account binds a channel that has none, an invitation never does - only its own proof binds it.
     * @type {AuthSubject}
     * @memberof KeycloakChannelUpsertRequest
     */
    subject?: AuthSubject;
    /**
     * 
     * @type {string}
     * @memberof KeycloakChannelUpsertRequest
     */
    targetAcr?: string;
}
/**
 * 
 * @export
 * @interface KeycloakInvitationView
 */
export interface KeycloakInvitationView {
    /**
     * 
     * @type {{ [key: string]: string; }}
     * @memberof KeycloakInvitationView
     */
    attributes: { [key: string]: string; };
    /**
     * 
     * @type {boolean}
     * @memberof KeycloakInvitationView
     */
    enabled: boolean;
    /**
     * 
     * @type {string}
     * @memberof KeycloakInvitationView
     */
    firstName: string;
    /**
     * 
     * @type {string}
     * @memberof KeycloakInvitationView
     */
    invitation: string;
    /**
     * 
     * @type {string}
     * @memberof KeycloakInvitationView
     */
    lastName: string;
    /**
     * 
     * @type {string}
     * @memberof KeycloakInvitationView
     */
    username: string;
}
/**
 * What the KOBIL SDK needs to activate this device.
 * @export
 * @interface KobilActivationStep
 */
export interface KobilActivationStep {
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    activationCode: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    kind: KobilActivationStepKindEnum;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilActivationStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    pin: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    tenantId: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    unlockSecret?: string;
}


/**
 * @export
 */
export const KobilActivationStepKindEnum = {
    kobil_activation: 'kobil-activation'
} as const;
export type KobilActivationStepKindEnum = typeof KobilActivationStepKindEnum[keyof typeof KobilActivationStepKindEnum];

/**
 * Waiting for the one-time password the KOBIL SDK produced.
 * @export
 * @interface KobilOtpStep
 */
export interface KobilOtpStep {
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    kind: KobilOtpStepKindEnum;
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    kobilPin?: string;
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilOtpStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    tenantId: string;
}


/**
 * @export
 */
export const KobilOtpStepKindEnum = {
    kobil_otp: 'kobil-otp'
} as const;
export type KobilOtpStepKindEnum = typeof KobilOtpStepKindEnum[keyof typeof KobilOtpStepKindEnum];

/**
 * 
 * @export
 * @interface KobilPinReleaseRequest
 */
export interface KobilPinReleaseRequest {
    /**
     * 
     * @type {KobilPinReleaseRequestUnlock}
     * @memberof KobilPinReleaseRequest
     */
    unlock: KobilPinReleaseRequestUnlock;
}
/**
 * @type KobilPinReleaseRequestUnlock
 * 
 * @export
 */
export type KobilPinReleaseRequestUnlock = BiometricUnlock | PasswordUnlock;
/**
 * 
 * @export
 * @interface KobilUnlockCredential
 */
export interface KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockCredential
     */
    kind: string;
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockCredential
     */
    userVerification?: KobilUnlockCredentialUserVerificationEnum;
}


/**
 * @export
 */
export const KobilUnlockCredentialUserVerificationEnum = {
    PIN: 'PIN',
    BIOMETRIC: 'BIOMETRIC'
} as const;
export type KobilUnlockCredentialUserVerificationEnum = typeof KobilUnlockCredentialUserVerificationEnum[keyof typeof KobilUnlockCredentialUserVerificationEnum];

/**
 * The app must unlock the backend-held PIN; these are the accepted ways.
 * @export
 * @interface KobilUnlockStep
 */
export interface KobilUnlockStep {
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockStep
     */
    kind: KobilUnlockStepKindEnum;
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockStep
     */
    tenantId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilUnlockStep
     */
    unlockOptions: Array<string>;
}


/**
 * @export
 */
export const KobilUnlockStepKindEnum = {
    kobil_unlock: 'kobil-unlock'
} as const;
export type KobilUnlockStepKindEnum = typeof KobilUnlockStepKindEnum[keyof typeof KobilUnlockStepKindEnum];

/**
 * A single candidate was auto-activated; this explains why the step appears.
 * @export
 * @interface MessageStep
 */
export interface MessageStep {
    /**
     * 
     * @type {string}
     * @memberof MessageStep
     */
    kind: MessageStepKindEnum;
    /**
     * 
     * @type {TextRef}
     * @memberof MessageStep
     */
    message: TextRef;
}


/**
 * @export
 */
export const MessageStepKindEnum = {
    message: 'message'
} as const;
export type MessageStepKindEnum = typeof MessageStepKindEnum[keyof typeof MessageStepKindEnum];

/**
 * The account's active authentication methods (docs/05-api.md #3a). Never contains fsc.
 * @export
 * @interface MethodsResponse
 */
export interface MethodsResponse {
    /**
     * 
     * @type {Array<ActiveMethodView>}
     * @memberof MethodsResponse
     */
    methods: Array<ActiveMethodView>;
}
/**
 * Which inputs this step is still waiting for.
 * @export
 * @interface MissingFields
 */
export interface MissingFields {
    /**
     * 
     * @type {string}
     * @memberof MissingFields
     */
    kind: MissingFieldsKindEnum;
    /**
     * 
     * @type {Array<string>}
     * @memberof MissingFields
     */
    missingFields: Array<string>;
}


/**
 * @export
 */
export const MissingFieldsKindEnum = {
    missing_fields: 'missing-fields'
} as const;
export type MissingFieldsKindEnum = typeof MissingFieldsKindEnum[keyof typeof MissingFieldsKindEnum];

/**
 * The user leaves for Nect's jump page; the app comes back with ?nectCaseId=... and reports it.
 * @export
 * @interface NectRedirectStep
 */
export interface NectRedirectStep {
    /**
     * 
     * @type {string}
     * @memberof NectRedirectStep
     */
    caseId: string;
    /**
     * 
     * @type {string}
     * @memberof NectRedirectStep
     */
    jumpUrl: string;
    /**
     * 
     * @type {string}
     * @memberof NectRedirectStep
     */
    kind: NectRedirectStepKindEnum;
}


/**
 * @export
 */
export const NectRedirectStepKindEnum = {
    nect_redirect: 'nect-redirect'
} as const;
export type NectRedirectStepKindEnum = typeof NectRedirectStepKindEnum[keyof typeof NectRedirectStepKindEnum];

/**
 * 
 * @export
 * @interface Next
 */
export interface Next {
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    context?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    step: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    toolId?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    toolSessionId?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    type: string;
}
/**
 * 
 * @export
 * @interface PasswordUnlock
 */
export interface PasswordUnlock extends KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof PasswordUnlock
     */
    password: string;
}


/**
 * 
 * @export
 * @interface Prompt
 */
export interface Prompt {
    /**
     * 
     * @type {TextRef}
     * @memberof Prompt
     */
    description?: TextRef;
    /**
     * 
     * @type {string}
     * @memberof Prompt
     */
    kind: string;
    /**
     * 
     * @type {TextRef}
     * @memberof Prompt
     */
    title?: TextRef;
}
/**
 * 
 * @export
 * @interface QrConfirmationCodeRequest
 */
export interface QrConfirmationCodeRequest {
    /**
     * 
     * @type {string}
     * @memberof QrConfirmationCodeRequest
     */
    confirmationCode?: string;
}
/**
 * A QR pairing in progress: the pairing code (browser), or the confirmation code to type into the browser (app, once).
 * @export
 * @interface QrPairingStep
 */
export interface QrPairingStep {
    /**
     * 
     * @type {string}
     * @memberof QrPairingStep
     */
    confirmationCode?: string;
    /**
     * 
     * @type {string}
     * @memberof QrPairingStep
     */
    kind: QrPairingStepKindEnum;
    /**
     * 
     * @type {string}
     * @memberof QrPairingStep
     */
    pairingCode?: string;
}


/**
 * @export
 */
export const QrPairingStepKindEnum = {
    qr_pairing: 'qr-pairing'
} as const;
export type QrPairingStepKindEnum = typeof QrPairingStepKindEnum[keyof typeof QrPairingStepKindEnum];

/**
 * The channel's current RestoreData, signed - null if there is nothing worth restoring yet.
 * @export
 * @interface RestoreDataResponse
 */
export interface RestoreDataResponse {
    /**
     * 
     * @type {string}
     * @memberof RestoreDataResponse
     */
    restoreData?: string;
}
/**
 * Several procedures are possible; the client shows a selection.
 * @export
 * @interface SelectMethodStep
 */
export interface SelectMethodStep {
    /**
     * 
     * @type {TextRef}
     * @memberof SelectMethodStep
     */
    description?: TextRef;
    /**
     * 
     * @type {string}
     * @memberof SelectMethodStep
     */
    kind: SelectMethodStepKindEnum;
    /**
     * 
     * @type {Array<string>}
     * @memberof SelectMethodStep
     */
    options: Array<string>;
    /**
     * 
     * @type {TextRef}
     * @memberof SelectMethodStep
     */
    title?: TextRef;
}


/**
 * @export
 */
export const SelectMethodStepKindEnum = {
    select_method: 'select-method'
} as const;
export type SelectMethodStepKindEnum = typeof SelectMethodStepKindEnum[keyof typeof SelectMethodStepKindEnum];

/**
 * @type StepData
 * What the current step needs to render. `kind` names the shape; see the mapping on this schema for the ones this deployment can produce.
 * @export
 */
export type StepData = { kind: 'confirm' } & ConfirmStep | { kind: 'enroll-password' } & EnrollPasswordStep | { kind: 'enroll-sms' } & EnrollSmsStep | { kind: 'failed-attempt' } & FailedAttemptStep | { kind: 'kobil-activation' } & KobilActivationStep | { kind: 'kobil-otp' } & KobilOtpStep | { kind: 'kobil-unlock' } & KobilUnlockStep | { kind: 'message' } & MessageStep | { kind: 'missing-fields' } & MissingFields | { kind: 'nect-redirect' } & NectRedirectStep | { kind: 'qr-pairing' } & QrPairingStep | { kind: 'select-method' } & SelectMethodStep;
/**
 * A text reference: look `key` up in the texts bundle (GET .../texts/{lang}), fill `{name}` placeholders from `args` (as is) or `texts` (resolved the same way, several joined with ", ").
 * @export
 * @interface TextRef
 */
export interface TextRef {
    /**
     * 
     * @type {{ [key: string]: string; }}
     * @memberof TextRef
     */
    args?: { [key: string]: string; };
    /**
     * 
     * @type {string}
     * @memberof TextRef
     */
    key: string;
    /**
     * The developer's wording, sent only while the texts bundle has none for key yet - show it instead of the key.
     * @type {string}
     * @memberof TextRef
     */
    template?: string;
    /**
     * 
     * @type {{ [key: string]: Array<TextRef>; }}
     * @memberof TextRef
     */
    texts?: { [key: string]: Array<TextRef>; };
}
/**
 * Mock Keycloak AccessToken (a spec-shaped unsecured JWT, alg=none - parse and display its payload, no verification needed) plus both token lifetimes. The RefreshToken value itself is deliberately never part of this response - it's a credential and stays server-side; refreshExpiresAt is the only thing about it exposed.
 * @export
 * @interface TokenResponse
 */
export interface TokenResponse {
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    accessExpiresAt: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    accessToken: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    refreshExpiresAt: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    tokenType?: string;
}
/**
 * 
 * @export
 * @interface ToolCatalogEntry
 */
export interface ToolCatalogEntry {
    /**
     * 
     * @type {TextRef}
     * @memberof ToolCatalogEntry
     */
    hint: TextRef;
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    method: string;
    /**
     * 
     * @type {TextRef}
     * @memberof ToolCatalogEntry
     */
    name: TextRef;
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    role: string;
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    toolId: string;
    /**
     * 
     * @type {Array<number>}
     * @memberof ToolCatalogEntry
     */
    versions: Array<number>;
}
