package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.kc.Loa1Login
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The public switch reaches the very same [Loa1LoginSwitch] as the admin one. */
class DemoLoa1LoginControllerTest {

    private val switch = mockk<Loa1LoginSwitch>(relaxed = true)
    private val controller = DemoLoa1LoginController(switch)

    @Test
    fun `reads and switches through the same switch as the admin endpoint`() {
        every { switch.current() } returns Loa1Login.ORCHESTRATOR
        assertThat(controller.get()).isEqualTo(Loa1LoginState(Loa1Login.ORCHESTRATOR))

        controller.put(Loa1LoginState(Loa1Login.KEYCLOAK_PASSWORD))
        verify { switch.switchTo(Loa1Login.KEYCLOAK_PASSWORD) }
    }
}
