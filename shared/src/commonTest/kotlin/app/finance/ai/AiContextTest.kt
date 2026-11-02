package app.finance.ai

import app.finance.ai.contract.AiContext
import app.finance.ai.contract.AiError
import app.finance.ai.contract.AiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.datetime.Instant

class AiContextTest {
    @Test fun selectedZoneAndReferenceAreExplicitAndCatalogIsCopied() {
        val input = mutableListOf(category())
        val value = context(input)
        input.clear()
        assertEquals("2026-11-02", value.localDate.toString())
        assertEquals(1, value.categories.size)
        assertEquals("Asia/Manila", value.settings.zoneId)
        assertEquals("AiContext", value.toString())
    }

    @Test fun duplicateIdentitiesAndOversizeCatalogAreRejectedWithoutTruncation() {
        for (items in listOf(listOf(category(), category()), (1..201).map { category(it) })) {
            val result = AiContext.create(Instant.parse("2026-11-02T00:00:00Z"), settings(), items)
            assertEquals(AiError.INVALID_CONTEXT, assertIs<AiResult.Failure>(result).error)
        }
        assertEquals(200, context((1..200).map { category(it) }).categories.size)
    }

    @Test fun unsupportedReferenceYearsFailTypedAndEmptyCatalogIsAllowed() {
        val result = AiContext.create(
            Instant.parse("+10000-01-01T00:00:00Z"),
            settings(),
            emptyList()
        )
        assertEquals(AiError.INVALID_CONTEXT, assertIs<AiResult.Failure>(result).error)
        assertEquals(0, context(emptyList()).categories.size)
    }
}
