package io.kotest.provided

import com.example.identity.contract.texts.Text
import com.example.identity.contract.texts.TextCatalog
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

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
    }
}
