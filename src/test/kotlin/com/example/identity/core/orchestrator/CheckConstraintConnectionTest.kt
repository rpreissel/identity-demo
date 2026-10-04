package com.example.identity.core.orchestrator

import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

/**
 * H2 turns a CHECK over a set of constants (`x IN ('a', 'b')`, also `x = 'a' OR x = 'b'`) into a
 * lookup that compares through the session that compiled the constraint: the pooled connection the
 * migration ran on. Once Hikari retires that connection (max-lifetime, 30 minutes), every insert
 * checked by the constraint fails with "The database has been closed". The checks therefore spell
 * out each value (`CASE x WHEN ...`, `x <> ... AND x <> ...`), and this test guards both sides.
 */
class CheckConstraintConnectionTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeScenario { stubDpopWithFakeJwk() }

        given("the migrated schema") {
            `when`("its CHECK constraints are read back") {
                val withValueSet = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_SCHEMA || '.' || CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CHECK_CLAUSE LIKE '%IN(%'",
                    String::class.java,
                )

                then("none holds a set of constants") {
                    withValueSet.shouldBeEmpty()
                }
            }
        }

        given("every pooled connection replaced, as Hikari does after its max-lifetime") {
            `when`("an app channel is opened, which inserts a checked row") {
                dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean.softEvictConnections()
                val channel = post("/orchestrator/api/v1/app/channels").channel()

                then("the insert passes its CHECK constraints") {
                    (channel["channelSessionId"] != null) shouldBe true
                }
            }
        }
    }
}
