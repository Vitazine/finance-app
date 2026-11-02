package app.finance.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal enum class JsonBoundaryError { OUTPUT_TOO_LONG, TOO_DEEP, INVALID_JSON, DUPLICATE_KEY }

/** Bound recursion before AST parsing; reject keys the AST would otherwise overwrite. */
internal fun strictJsonBoundary(raw: String, maxChars: Int, maxDepth: Int): JsonBoundaryError? {
    if (raw.length > maxChars) return JsonBoundaryError.OUTPUT_TOO_LONG
    val stack = mutableListOf<MutableSet<String>?>()
    var i = 0
    while (i < raw.length) {
        when (raw[i]) {
            '{', '[' -> {
                if (stack.size == maxDepth) return JsonBoundaryError.TOO_DEEP
                stack.add(if (raw[i] == '{') mutableSetOf() else null)
            }
            '}', ']' -> {
                if (stack.isEmpty()) return JsonBoundaryError.INVALID_JSON
                val objectFrame = stack.removeAt(stack.lastIndex)
                if ((objectFrame != null) != (raw[i] == '}')) return JsonBoundaryError.INVALID_JSON
            }
            '"' -> {
                val start = i++
                var closed = false
                while (i < raw.length) {
                    if (raw[i] < ' ') return JsonBoundaryError.INVALID_JSON
                    if (raw[i] == '\\') {
                        i += 2
                    } else if (raw[i] == '"') {
                        closed = true
                        break
                    } else {
                        i++
                    }
                }
                if (!closed) return JsonBoundaryError.INVALID_JSON
                var next = i + 1
                while (next < raw.length && raw[next] in " \t\r\n") next++
                if (next < raw.length && raw[next] == ':' && stack.lastOrNull() != null) {
                    val key = try {
                        (
                            Json.parseToJsonElement(
                                raw.substring(start, i + 1)
                            ) as JsonPrimitive
                            ).content
                    } catch (_: IllegalArgumentException) {
                        return JsonBoundaryError.INVALID_JSON
                    }
                    if (!stack.last()!!.add(key)) return JsonBoundaryError.DUPLICATE_KEY
                }
            }
        }
        i++
    }
    return if (stack.isEmpty()) null else JsonBoundaryError.INVALID_JSON
}
