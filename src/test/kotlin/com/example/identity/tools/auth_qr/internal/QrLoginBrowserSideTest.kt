package com.example.identity.tools.auth_qr.internal

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.tools.auth_qr.QR_LOGIN_TTL
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.Optional

private const val PAIRING = "PAIRING1"
private const val RIGHT_CODE = "123456"

/**
 * Holds one pairing request in the mocked repository; [RIGHT_CODE] confirms it once it is
 * APPROVED, any other code does not.
 */
private class Fixture(
    status: QrLoginStatus,
    expectedAccountId: AccountId? = null,
    resolvingAccountId: AccountId? = null,
    confirmationAttempts: Int = 0,
    expiresAt: Instant = TEST_NOW.plusSeconds(60),
) {
    val digest = ConfirmationCodeDigest("test-pepper", TEST_CLOCK)
    val request = QrLoginRequest(pairingCode = PAIRING, expectedAccountId = expectedAccountId, createdAt = TEST_NOW).apply {
        this.status = status
        this.resolvingAccountId = resolvingAccountId
        this.confirmationAttempts = confirmationAttempts
        this.expiresAt = expiresAt
    }
    val requests = mockk<QrLoginRequestRepository>().also {
        every { it.findById(any()) } returns Optional.empty()
        every { it.findById(PAIRING) } returns Optional.of(request)
        every { it.completeIfConfirmed(PAIRING, any(), any()) } returns 0
        every { it.completeIfConfirmed(PAIRING, digest.digest(RIGHT_CODE), any()) } returns 1
        every { it.countWrongConfirmation(PAIRING, any()) } returns 1
    }
    val browserSide = QrLoginBrowserSide(requests, digest, clock = TEST_CLOCK)
}

/**
 * The browser's half of a QR login, shared by `auth-qr` and `auth-qr-lookup`: which pairing state
 * becomes which step. The handler tests only cover how each step becomes an outcome.
 */
class QrLoginBrowserSideTest : BehaviorSpec({

    given("no open pairing") {
        val f = Fixture(QrLoginStatus.PENDING)
        val saved = slot<QrLoginRequest>()
        every { f.requests.save(capture(saved)) } answers { saved.captured }

        `when`("a pairing is opened for account 42") {
            val pairingCode = f.browserSide.open(expectedAccountId = AccountId(42L))

            then("it stores a request under the returned code that expects account 42 and runs out after the TTL") {
                saved.captured.pairingCode shouldBe pairingCode
                saved.captured.expectedAccountId shouldBe AccountId(42L)
                saved.captured.status shouldBe QrLoginStatus.PENDING
                saved.captured.expiresAt shouldBe TEST_NOW.plus(QR_LOGIN_TTL)
            }
        }
    }

    given("a pairing code the repository does not know") {
        val f = Fixture(QrLoginStatus.PENDING)

        `when`("the browser polls") {
            val state = f.browserSide.advance("UNKNOWN1", null)

            then("it reads as expired") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("QR-Code abgelaufen"))
            }
        }
    }

    given("a pending pairing the app has not decided on") {
        val f = Fixture(QrLoginStatus.PENDING)

        `when`("the browser polls") {
            val state = f.browserSide.advance(PAIRING, null)

            then("it keeps waiting for the app") {
                state shouldBe QrLoginBrowserSide.State.WaitingForApp
            }
        }
    }

    given("a pending pairing that ran out before the app decided") {
        val f = Fixture(QrLoginStatus.PENDING, expiresAt = TEST_NOW.minusSeconds(1))

        `when`("the browser polls") {
            val state = f.browserSide.advance(PAIRING, null)

            then("it fails as expired") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("QR-Code abgelaufen"))
            }
        }
    }

    given("a pairing account 99 approved, opened for account 42") {
        val f = Fixture(QrLoginStatus.APPROVED, expectedAccountId = AccountId(42L), resolvingAccountId = AccountId(99L))

        `when`("the browser polls without a code") {
            val state = f.browserSide.advance(PAIRING, null)

            then("it asks for the confirmation code the app shows") {
                state shouldBe QrLoginBrowserSide.State.EnterCode
            }
        }

        `when`("the browser sends the right confirmation code") {
            val state = f.browserSide.advance(PAIRING, RIGHT_CODE)

            then("it is confirmed by account 99, and still names the account it was opened for") {
                state shouldBe QrLoginBrowserSide.State.Confirmed(AccountId(99L), expectedAccountId = AccountId(42L))
            }
        }
    }

    given("an approved pairing with attempts left") {
        val f = Fixture(QrLoginStatus.APPROVED, resolvingAccountId = AccountId(99L))

        `when`("the browser sends a wrong confirmation code") {
            val state = f.browserSide.advance(PAIRING, "000000")

            then("it fails as a wrong code") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("Bestätigungscode falsch"))
            }

            then("it counts the wrong code on the pairing") {
                verify { f.requests.countWrongConfirmation(PAIRING, PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS) }
            }
        }
    }

    given("an approved pairing with its last confirmation attempt left") {
        val f = Fixture(QrLoginStatus.APPROVED, resolvingAccountId = AccountId(99L), confirmationAttempts = PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS - 1)

        `when`("the browser sends another wrong code") {
            val state = f.browserSide.advance(PAIRING, "000000")

            then("it says the pairing is burned") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("Zu viele falsche Bestätigungscodes. Bitte starten Sie die Anmeldung per QR-Code neu."))
            }
        }
    }

    given("an approved pairing that ran out before the code arrived") {
        val f = Fixture(QrLoginStatus.APPROVED, resolvingAccountId = AccountId(99L), expiresAt = TEST_NOW.minusSeconds(1))

        `when`("the browser sends the right confirmation code") {
            val state = f.browserSide.advance(PAIRING, RIGHT_CODE)

            then("it fails as expired, without trying the code") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("QR-Code abgelaufen"))
                verify(exactly = 0) { f.requests.completeIfConfirmed(any(), any(), any()) }
            }
        }
    }

    given("a pairing another browser already completed") {
        val f = Fixture(QrLoginStatus.COMPLETED, resolvingAccountId = AccountId(99L))

        `when`("the confirmation code is replayed") {
            val state = f.browserSide.advance(PAIRING, RIGHT_CODE)

            then("it does not log in a second browser") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
            }
        }
    }

    given("a pairing the app declined") {
        val f = Fixture(QrLoginStatus.DENIED)

        `when`("the browser polls") {
            val state = f.browserSide.advance(PAIRING, null)

            then("it fails as declined") {
                state shouldBe QrLoginBrowserSide.State.Failed(Text("Vom Nutzer abgelehnt"))
            }
        }
    }
})
