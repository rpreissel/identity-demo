package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.retention.RetentionJob
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.AppTokenVault
import com.example.identity.core.orchestrator.session.DataKey
import com.example.identity.core.orchestrator.session.DataKeyRepository
import com.example.identity.core.orchestrator.session.RetentionClassKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired

/**
 * ADR-53 against the real schema: a running tool session's working data and a session's cached
 * tokens are unreadable in their tables, the day's data key exists, and a retired key goes with the
 * hourly sweep while the current one stays.
 */
class WorkingDataEncryptionDbTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var dataKeys: DataKeyRepository

    @Autowired
    private lateinit var retentionClassKeys: RetentionClassKeys

    @Autowired
    private lateinit var retentionJob: RetentionJob

    @Autowired
    private lateinit var appTokenSessionRepository: AppTokenSessionRepository

    @Autowired
    private lateinit var appTokenVault: AppTokenVault

    init {
        beforeScenario { stubDpopWithFakeJwk() }

        given("an SMS enrollment that has sent its TAN and waits for it") {
            `when`("the tool has saved its working data - the number and the TAN's hash") {
                val channel = identifyAndConfirmEmail()
                val enrollToolSessionId = post("/tools/api/enroll-sms/v1?channel=$channel").nextRaw()["toolSessionId"] as String
                captureMockTan { patch("/tools/api/enroll-sms/v1/$enrollToolSessionId", """{"phoneNumber":"+49 170 1234567"}""") }

                then("the row holds a ciphertext under the day's key, nothing readable") {
                    val rows = jdbcTemplate.queryForList(
                        "select data, data_key_id, data_type from orchestrator.tool_session where status = 'RUNNING' and data is not null"
                    )
                    rows.size shouldNotBe 0
                    rows.forEach { row ->
                        val stored = String(row["DATA"] as ByteArray, Charsets.ISO_8859_1)
                        stored shouldNotContain "1234567"
                        stored shouldNotContain "phoneNumber"
                        (row["DATA_KEY_ID"] as String) shouldStartWith "TOOL_SESSION:"
                        dataKeys.findById(row["DATA_KEY_ID"] as String).isPresent shouldBe true
                    }
                }
            }
        }

        given("a registration whose journey got its key before the account existed") {
            `when`("ident-fsc creates the account and enroll-sms seals the number") {
                val channel = identifyAndConfirmEmail()
                enrollSms(channel)
                val accountId = AccountId(jdbcTemplate.queryForObject("SELECT account_id FROM orchestrator.channel_session WHERE id = ?", Long::class.java, UUID.fromString(channel))!!)

                then("the journey's key is the account's primary key, and the number's row names it") {
                    val keys = jdbcTemplate.queryForList("SELECT key_id FROM account.master_key WHERE account_id = ? AND primary_key = TRUE", UUID::class.java, accountId.value)
                    keys.size shouldBe 1
                    val numberKey = jdbcTemplate.queryForObject("SELECT key_id FROM auth_sms.enrollment", UUID::class.java)
                    numberKey shouldBe keys.single()
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.master_key WHERE account_id IS NULL", Int::class.java) shouldBe 0
                }
            }
        }

        given("a signed-in app channel") {
            `when`("its token is cached") {
                val channel = UUID.fromString(loginAsSeededAccount())
                val appTokenSessionId = jdbcTemplate.queryForObject(
                    "SELECT app_token_session_id FROM orchestrator.channel_session WHERE id = ?", UUID::class.java, channel
                )!!

                then("the stored token is not the token, but the vault gives it back") {
                    val raw = jdbcTemplate.queryForObject("SELECT access_token FROM orchestrator.app_token_session WHERE id = ?", ByteArray::class.java, appTokenSessionId)!!
                    String(raw, Charsets.ISO_8859_1) shouldNotContain "eyJ"
                    appTokenVault.accessTokenOf(appTokenSessionRepository.findById(appTokenSessionId).get())!! shouldStartWith "eyJ"
                }
            }
        }

        given("a data key past its retirement and the current one") {
            `when`("the retention job cleans up") {
                val current = retentionClassKeys.currentToolSessionKey().keyId
                val retired = "TOOL_SESSION:2000-01-01"
                dataKeys.saveAndFlush(
                    DataKey(
                        keyId = retired, retentionClass = RetentionClassKeys.TOOL_SESSION, wrappedKey = ByteArray(60), kekVersion = "1",
                        createdAt = Instant.parse("2000-01-01T00:00:00Z"), retireAfter = Instant.parse("2000-01-10T00:00:00Z")
                    )
                )

                retentionJob.cleanup()

                then("the retired key is gone and the current one stays") {
                    dataKeys.findById(retired).isPresent shouldBe false
                    dataKeys.findById(current).isPresent shouldBe true
                }
            }
        }
    }
}
