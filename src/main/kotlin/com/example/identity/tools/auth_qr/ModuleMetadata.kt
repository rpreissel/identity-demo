package com.example.identity.tools.auth_qr

import org.springframework.modulith.ApplicationModule

/**
 * QR-Login: a WEB channel shows a pairing/verification code (`auth-qr`/`auth-qr-lookup`), an
 * already-authenticated APP channel approves or declines it (`confirm-qr-login`), both sides of
 * the same `method="qr"` credential family living in one module like every other pair
 * (docs/08-projektrahmen.md M11). Talks to the orchestrator through `tool_api`
 * only, exactly like every other method module (docs/03-tool-architektur.md #2).
 */
@ApplicationModule(id = "auth_qr", allowedDependencies = ["tool_api", "texts"])
internal class ModuleMetadata
