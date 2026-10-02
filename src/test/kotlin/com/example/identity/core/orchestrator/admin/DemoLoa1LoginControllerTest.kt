package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.keycloak.Loa1Login
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.mockk
import io.mockk.verify

/** The public switch reaches the very same [Loa1LoginSwitch] as the admin one. */
class DemoLoa1LoginControllerTest : BehaviorSpec({

    given("the public loa1 login endpoint") {
        val switch = mockk<Loa1LoginSwitch>(relaxed = true)
        val controller = DemoLoa1LoginController(switch)

        `when`("a visitor switches to Keycloak's password") {
            controller.put(Loa1LoginState(Loa1Login.KEYCLOAK_PASSWORD))

            then("it goes through the same switch as the admin endpoint") {
                verify { switch.switchTo(Loa1Login.KEYCLOAK_PASSWORD) }
            }
        }
    }
})
