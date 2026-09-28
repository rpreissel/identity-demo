package com.example.identity.demo.demo_mode


/**
 * A bean, usually a controller, that exists only in demo mode (ADR-36): the unauthenticated
 * surfaces of the simulated foreign systems and the demo's own switches. With real people each
 * would be a way to take over accounts, so they are absent. `ApiBoundaryArchitectureTest` requires
 * it on every foreign-system controller.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@OnlyInDemoMode
annotation class DemoSurface
