package com.example.identity.contract.tool_api.ids

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Stores every [AccountId] property as its number. Needed because a nullable `AccountId?` is a
 * boxed object on the JVM; UUID- and String-based ids are stored as they are. For the same reason
 * a repository method takes `AccountId?`: a non-null parameter reaches Hibernate as a bare `long`.
 */
@Converter(autoApply = true)
class AccountIdConverter : AttributeConverter<AccountId, Long> {
    override fun convertToDatabaseColumn(attribute: AccountId?): Long? = attribute?.value
    override fun convertToEntityAttribute(dbData: Long?): AccountId? = dbData?.let(::AccountId)
}
