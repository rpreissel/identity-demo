package com.example.identity.demo.demo_mode

import org.springframework.modulith.ApplicationModule

/**
 * What exists only in demo mode (`demo.mode`, ADR-36) - as a library: [DemoSurface]. Knows nothing
 * else, so the simulated foreign systems may depend on it like on `texts`.
 */
@ApplicationModule(id = "demo_mode", allowedDependencies = [])
internal class DemoModeModule
