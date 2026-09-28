package com.example.identity.contract.tool_api

import com.example.identity.contract.texts.Text

/**
 * A tool refuses to repeat something it bounds itself, such as sending another code
 * (`HTTP 429`, carrying [text]). Only where the refusal reveals nothing the caller does not
 * already know; a lookup tool folds its limits into its ordinary failure instead (ADR-44).
 */
class TooManyRequestsException(val text: Text) : RuntimeException(text.template)
