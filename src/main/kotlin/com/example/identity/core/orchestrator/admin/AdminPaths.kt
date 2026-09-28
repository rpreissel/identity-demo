package com.example.identity.core.orchestrator.admin

/**
 * Where operator endpoints live, deliberately not under `API_V1`. The app contract is everything
 * under `API_V1`. These endpoints switch things for the whole deployment, and an app client must
 * never depend on them. Operators and apps also change on different schedules.
 */
const val ADMIN_API = "/orchestrator/admin"

/**
 * Public demo endpoints, outside the app contract like [ADMIN_API], but without its login.
 * Read-only, except the login theme ([DemoLoginThemeController]) and the start of a web sign-in
 * ([DemoLoa1LoginController]), which every visitor may switch to compare both variants, and the
 * demo reset on the welcome page ([DemoSessionsController]).
 */
const val DEMO_API = "/orchestrator/demo"
