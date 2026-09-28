package com.example.identity.simulation.mail

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** One mail the simulated server "sent". */
data class SentMail(val sequence: Long, val address: String, val code: String, val sentAt: Instant)

/**
 * The simulated mail server. A sent mail lands in an in-memory [outbox] (the newest
 * [OUTBOX_SIZE]). The code never goes to a log, whatever the demo mode; the log names only the
 * address's domain. The tester reads the code on the page `/briefkasten/` (`/mock-mail/outbox`).
 */
@Service
class MailServer {

    private val outbox = ConcurrentLinkedDeque<SentMail>()
    private val sequence = AtomicLong()

    fun sendCode(address: String, code: String) {
        outbox.addFirst(SentMail(sequence.incrementAndGet(), address, code, Instant.now()))
        while (outbox.size > OUTBOX_SIZE) outbox.pollLast()
        log.info("Simulated mail sent to ***@{}", address.substringAfter('@', "?"))
    }

    /** Newest first. */
    fun outbox(): List<SentMail> = outbox.toList()

    private companion object {
        const val OUTBOX_SIZE = 100
        val log = LoggerFactory.getLogger(MailServer::class.java)
    }
}
