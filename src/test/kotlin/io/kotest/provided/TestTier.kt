package io.kotest.provided

import io.kotest.core.descriptors.Descriptor
import io.kotest.engine.extensions.filter.DescriptorFilter
import io.kotest.engine.extensions.filter.DescriptorFilterResult
import org.springframework.boot.test.context.SpringBootTest

/**
 * The two ways to run the suite (docs/13-ausfuehren.md #7): `quick` leaves out every spec that
 * starts a Spring context, `full` runs everything. Derived from `@SpringBootTest` (inherited
 * through `IntegrationTestSupport`), so no spec carries a marker that could go stale.
 */
enum class TestTier {
    QUICK, FULL;

    companion object {
        /** From `-Dtest.tier=quick|full`; the Gradle task `quickTest` sets it, `test` leaves it. */
        val current: TestTier = System.getProperty("test.tier")?.uppercase()?.let(::valueOf) ?: FULL
    }
}

/**
 * Excludes every Spring-context spec when the tier is [TestTier.QUICK]. A [DescriptorFilter] acts
 * before the spec is instantiated, so no context starts; the spec is reported as ignored.
 */
class TestTierExtension : DescriptorFilter {
    override fun filter(descriptor: Descriptor): DescriptorFilterResult {
        if (TestTier.current == TestTier.FULL || !descriptor.isSpec()) return DescriptorFilterResult.Include
        val spec = runCatching { Class.forName(descriptor.id.value) }.getOrNull() ?: return DescriptorFilterResult.Include
        return if (spec.isAnnotationPresent(SpringBootTest::class.java)) {
            DescriptorFilterResult.Exclude("starts a Spring context; runs in the full tier only (./gradlew test)")
        } else {
            DescriptorFilterResult.Include
        }
    }
}
