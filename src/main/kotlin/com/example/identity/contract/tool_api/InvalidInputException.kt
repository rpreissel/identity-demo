package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text

/**
 * A value the user entered was rejected (a malformed phone number, a too short password) -
 * `HTTP 400` carrying [text]. An [IllegalArgumentException] like every rejected input; this one
 * says so in words the user reads, where a plain `require` only names the technical fault.
 */
class InvalidInputException(val text: Text) : IllegalArgumentException(text.template)
