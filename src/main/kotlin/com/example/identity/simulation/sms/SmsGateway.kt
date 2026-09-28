package com.example.identity.simulation.sms

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** One SMS the simulated provider "sent". */
data class SentSms(val sequence: Long, val phoneNumber: String, val tan: String, val sentAt: Instant)

/**
 * The simulated SMS provider. A sent SMS lands in an in-memory [outbox] (the newest
 * [OUTBOX_SIZE]). The TAN never goes to a log, whatever the demo mode; the log names only the
 * last digits of the number. The tester reads the TAN on the page `/briefkasten/`
 * (`/mock-sms/outbox`).
 */
@Service
class SmsGateway(private val clock: Clock) {

    private val outbox = ConcurrentLinkedDeque<SentSms>()
    private val sequence = AtomicLong()

    fun sendTan(phoneNumber: String, tan: String) {
        outbox.addFirst(SentSms(sequence.incrementAndGet(), phoneNumber, tan, clock.instant()))
        while (outbox.size > OUTBOX_SIZE) outbox.pollLast()
        log.info("Simulated SMS sent to {}", masked(phoneNumber))
    }

    /** Newest first. */
    fun outbox(): List<SentSms> = outbox.toList()

    private fun masked(phoneNumber: String): String =
        "***" + phoneNumber.filter(Char::isDigit).takeLast(3)

    private companion object {
        const val OUTBOX_SIZE = 100
        val log = LoggerFactory.getLogger(SmsGateway::class.java)
    }
}
