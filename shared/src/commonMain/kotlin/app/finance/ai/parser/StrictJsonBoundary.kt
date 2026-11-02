package app.finance.ai.parser

import app.finance.ai.contract.AiError
import app.finance.core.json.strictJsonBoundary

internal fun jsonBoundary(raw: String): AiError? =
    strictJsonBoundary(raw, 32768, 8)?.let { AiError.valueOf(it.name) }
