package com.example.identity.core.orchestrator

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Every `@Scheduled` job of the application, by class, and what it does. `ScheduledJobsTest` keeps
 * this list complete in both directions, so the documentation cannot count fewer jobs than there
 * are. All of them are idempotent: a second run deletes what the first left, or nothing. Run on
 * every instance, they cost doubled work, not wrong data.
 */
val SCHEDULED_JOBS: Map<String, String> = mapOf(
    "RetentionJob" to "Aufbewahrung der Sitzungen, Journeys, Ablaufprotokoll, Zaehler (stuendlich)",
    "ToolSessionRetentionDriver" to "Arbeitsdaten der Tool-Sessions aller Module (stuendlich)",
    "DpopReplayProtectionService" to "Schutz vor wiederholten DPoP-Proofs (minuetlich)",
    "ChangeLogRetention" to "Aenderungsprotokoll geloeschter Konten (taeglich)",
    "SignInLogRetention" to "Anmeldeprotokoll (taeglich)",
    "ClaimBatchKeyRetention" to "Datenschluessel abgelaufener Claim-Gruppen (taeglich)",
    "RetentionClassKeys" to "Tagesschluessel der Arbeitsdaten vorab anlegen (stuendlich)",
)

/**
 * States in one place that this system runs as a single instance (docs/07-betrieb.md Abschnitt 3b).
 * `deployment.instances=multiple` enables nothing. It is a claim about the environment, which
 * [DeploymentTopologyCheck] checks against what the code supports.
 */
@ConfigurationProperties(prefix = "deployment")
data class DeploymentProperties(
    /**
     * `single` (default) or `multiple`. A deployment that genuinely runs more than one instance
     * must say so, and will then be told what is still missing rather than misbehaving silently.
     */
    val instances: Instances = Instances.SINGLE
) {
    enum class Instances { SINGLE, MULTIPLE }
}

@Component
class DeploymentTopologyCheck(
    private val properties: DeploymentProperties,
    @Value("\${identity.secrets.otp-pepper:}") private val otpPepper: String
) {
    private val log = LoggerFactory.getLogger(DeploymentTopologyCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        if (properties.instances == DeploymentProperties.Instances.SINGLE) {
            log.info(
                "Deployment: eine Instanz (deployment.instances=single). Geplante Jobs laufen ohne " +
                    "Sperre, das OTP-Pepper darf leer bleiben."
            )
            return
        }

        val missing = buildList {
            if (otpPepper.isBlank()) {
                add(
                    "identity.secrets.otp-pepper ist leer - dann erzeugt jede Instanz beim Start ein eigenes " +
                        "zufaelliges Pepper, und keine kann die SMS-/E-Mail-Codes der anderen pruefen. " +
                        "Einen gemeinsamen Wert setzen."
                )
            }
            // Unconditional: nothing coordinates the schedulers across instances.
            add(
                "Die ${SCHEDULED_JOBS.size} geplanten Jobs (${SCHEDULED_JOBS.keys.joinToString()}) haben " +
                    "keine Leader-Election und keine Sperre - bei mehreren Instanzen laufen sie mehrfach " +
                    "parallel. Sie sind idempotent, aber gleichzeitige Loeschlaeufe auf denselben Zeilen " +
                    "sind nicht erprobt; eine Sperre (ShedLock o. ae.) fehlt noch (docs/07-betrieb.md " +
                    "Abschnitt 3b)."
            )
            add(
                "RestoreDataCodec erzeugt sein Signaturgeheimnis je Prozess - ein RestoreData-Token " +
                    "einer Instanz ist fuer die andere unlesbar (Web-Anmeldung muss neu beginnen)."
            )
        }

        error(
            "deployment.instances=multiple, aber dieses System traegt das noch nicht:\n" +
                missing.joinToString("\n") { "  - $it" }
        )
    }
}
