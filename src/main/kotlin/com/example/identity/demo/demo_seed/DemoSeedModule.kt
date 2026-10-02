package com.example.identity.demo.demo_seed

import org.springframework.modulith.ApplicationModule

/**
 * Demo-only test data and nothing else: the test persons, their Freischaltcodes and letters in
 * `db/migration/demo_seed/V16__testdata.sql`. No accounts - a test person registers like anyone
 * else (docs/08-projektrahmen.md, M13). Outside demo mode the folder is not migrated.
 */
@ApplicationModule(id = "demo_seed", allowedDependencies = [])
internal class DemoSeedModule
