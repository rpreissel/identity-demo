package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.Journeys
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Stores every [Journey] property by its name ([Journeys.byName]), so the stored values are the
 * names on the wire and in the journey trace.
 */
@Converter(autoApply = true)
class JourneyConverter : AttributeConverter<Journey<*>, String> {
    override fun convertToDatabaseColumn(attribute: Journey<*>?): String? = attribute?.name
    override fun convertToEntityAttribute(dbData: String?): Journey<*>? = dbData?.let(Journeys::byName)
}
