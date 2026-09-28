package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text

/**
 * A tool module refuses a request that does not fit the account's current state: 409, like the
 * orchestrator's own `invalidState` (docs/07-betrieb.md #1). [text] is shown to the user.
 */
class InvalidStateException(val text: Text) : RuntimeException(text.template)
