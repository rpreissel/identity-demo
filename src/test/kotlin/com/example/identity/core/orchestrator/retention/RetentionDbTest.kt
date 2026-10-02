package com.example.identity.core.orchestrator.retention

import com.example.identity.core.orchestrator.IntegrationTestSupport
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Retention against the real schema: a channel past its window goes with everything it owns - its journeys
 * and their tool sessions, its AppTokenSession and its evidence - while a live channel and the account itself
 * stay. [RetentionJobTest] checks the order of calls; this checks the rows.
 */
class RetentionDbTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var retentionJob: RetentionJob

    private fun count(sql: String, vararg args: Any): Long = jdbcTemplate.queryForObject(sql, Long::class.java, *args)!!

    init {
        beforeScenario { stubDpopWithFakeJwk() }

        given("a logged-in channel past its retention window and a live one") {
            `when`("the retention job cleans up") {
                val old = UUID.fromString(loginAsSeededAccount())
                val live = UUID.fromString(post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String)
                val row = jdbcTemplate.queryForMap(
                    "SELECT account_id, app_token_session_id, session_evidence_id FROM orchestrator.channel_session WHERE id = ?", old
                )
                val accountId = row["ACCOUNT_ID"] as Long
                val appTokenSessionId = row["APP_TOKEN_SESSION_ID"] as UUID
                val sessionEvidenceId = row["SESSION_EVIDENCE_ID"] as UUID
                val journeys = jdbcTemplate.queryForList("SELECT id FROM orchestrator.auth_journey WHERE channel_session_id = ?", UUID::class.java, old)
                // Past the channel window (14 days after expiry), nothing else touched.
                jdbcTemplate.update("UPDATE orchestrator.channel_session SET expires_at = DATEADD('DAY', -15, CURRENT_TIMESTAMP) WHERE id = ?", old)

                retentionJob.cleanup()

                then("the old channel goes") {
                    count("SELECT COUNT(*) FROM orchestrator.channel_session WHERE id = ?", old) shouldBe 0
                }
                then("its journeys and their tool sessions go with it") {
                    journeys.shouldNotBeEmpty()
                    count("SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ?", old) shouldBe 0
                    journeys.forEach { count("SELECT COUNT(*) FROM orchestrator.tool_session WHERE journey_id = ?", it!!) shouldBe 0 }
                }
                then("its evidence and its AppTokenSession go with it") {
                    count("SELECT COUNT(*) FROM orchestrator.session_evidence WHERE id = ?", sessionEvidenceId) shouldBe 0
                    count("SELECT COUNT(*) FROM orchestrator.app_token_session WHERE id = ?", appTokenSessionId) shouldBe 0
                }
                then("the live channel and the account stay") {
                    count("SELECT COUNT(*) FROM orchestrator.channel_session WHERE id = ?", live) shouldBe 1
                    count("SELECT COUNT(*) FROM account.account WHERE id = ?", accountId) shouldBe 1
                }
            }
        }
    }
}
