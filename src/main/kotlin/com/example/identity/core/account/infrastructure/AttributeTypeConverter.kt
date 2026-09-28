package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.claims.AttributeType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Stores [AttributeType] as its [AttributeType.wireName] (`person_id`), not the constant name:
 * the rows on disk hold wire names, and the default encoding would silently stop matching them.
 */
@Converter
class AttributeTypeConverter : AttributeConverter<AttributeType, String> {
    override fun convertToDatabaseColumn(attribute: AttributeType?): String? = attribute?.wireName
    override fun convertToEntityAttribute(dbData: String?): AttributeType? =
        // A stored name that is no AttributeType is corrupt data, not input to refuse politely.
        dbData?.let { checkNotNull(AttributeType.fromWireName(it)) { "Unknown attribute type in the database: $it" } }
}
