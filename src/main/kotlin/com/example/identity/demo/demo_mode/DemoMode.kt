package com.example.identity.demo.demo_mode

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Name

/** The one switch of ADR-36. Read only here and through the types below, so every reader agrees on its default. */
const val DEMO_MODE_PROPERTY = "demo.mode"

/** `demo.mode`, as a bean: whether this instance is a demo. Missing counts as on, as in `application.yml`. */
@ConfigurationProperties(prefix = "demo")
data class DemoMode(@param:Name("mode") val on: Boolean = true)

/** A bean that exists only in demo mode. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = [DEMO_MODE_PROPERTY], havingValue = "true", matchIfMissing = true)
annotation class OnlyInDemoMode

/** A bean that exists only outside demo mode. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = [DEMO_MODE_PROPERTY], havingValue = "false")
annotation class OutsideDemoMode
