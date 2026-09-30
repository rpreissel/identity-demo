package io.kotest.provided

import com.example.identity.contract.texts.Text
import com.example.identity.contract.texts.TextCatalog
import com.example.identity.contract.tool_api.values.Email
import com.example.identity.contract.tool_api.values.Kvnr
import com.example.identity.contract.tool_api.values.MemberNumber
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.values.PhoneNumber
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension
import io.mockk.registerInstanceFactory

/**
 * Registers SpringExtension globally so specs can constructor-inject Spring beans, and the
 * [TestTierExtension] that leaves Spring-context specs out of a quick run.
 *
 * The package is fixed: Kotest 6 loads exactly `io.kotest.provided.ProjectConfig` and does not scan
 * the classpath. Anywhere else this class would silently not run.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension(), TestTierExtension())

    init {
        // Runtime guard behind TextCatalog's static analysis (docs/adr/ADR-033): every text sent
        // out must be in the catalog, otherwise no language has a wording for it.
        Text.onWire = { text ->
            check(text.id in TextCatalog.application.ids) {
                "Text not in the catalog (template not a literal?): \"${text.template}\""
            }
        }
        // MockK builds a placeholder for `any()` through the constructor of a value class, and a
        // random string fails these format checks. A valid value per class keeps `any()` usable.
        registerInstanceFactory { PartnerNumber("P000000000") }
        registerInstanceFactory { Email("any@example.com") }
        registerInstanceFactory { Kvnr("A000000000") }
        registerInstanceFactory { MemberNumber("00000000") }
        registerInstanceFactory { PhoneNumber("+491700000000") }
    }
}
