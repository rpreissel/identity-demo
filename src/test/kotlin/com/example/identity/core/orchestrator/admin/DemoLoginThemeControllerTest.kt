package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.keycloak.LoginTheme
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.mockk
import io.mockk.verify

/**
 * The public switch is the admin one without the login - it must reach the very same
 * [LoginThemeSwitch], so both pages always agree on which theme is active.
 */
class DemoLoginThemeControllerTest : BehaviorSpec({

    given("the public login theme endpoint") {
        val switch = mockk<LoginThemeSwitch>(relaxed = true)
        val controller = DemoLoginThemeController(switch)

        `when`("a visitor switches to FreeMarker") {
            controller.put(LoginThemeState(LoginTheme.FREEMARKER))

            then("it goes through the same switch as the admin endpoint") {
                verify { switch.switchTo(LoginTheme.FREEMARKER) }
            }
        }
    }
})
