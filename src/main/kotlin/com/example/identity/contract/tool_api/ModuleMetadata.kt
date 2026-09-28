package com.example.identity.contract.tool_api

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.ApplicationModule.Type

/**
 * Package descriptor for Spring Modulith (docs/08-projektrahmen.md). The contract between the
 * orchestrator and the method modules: depends only on `texts`, so every module can depend on it
 * without depending on each other. OPEN because a contract has nothing to hide: all of its
 * packages are public. A `package-info.kt` with `@file:ApplicationModule` would compile to no
 * class and lose the annotation.
 */
@ApplicationModule(id = "tool_api", allowedDependencies = ["texts"], type = Type.OPEN)
internal class ModuleMetadata
