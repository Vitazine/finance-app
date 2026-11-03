package app.finance.domain.ledger

/** Starter expense taxonomy; installation is requested by the Android bootstrap, never a read. */
object DefaultCategories {
    private data class Definition(val code: String, val name: String, val id: LedgerId)
    private val definitions = listOf(
        "health" to "Здоровье",
        "utilities" to "Коммунальные услуги",
        "other" to "Прочие расходы",
        "shopping" to "Покупки",
        "housing" to "Жильё",
        "groceries" to "Продукты",
        "transport" to "Транспорт",
        "education" to "Образование",
        "entertainment" to "Развлечения",
        "cafes" to "Кафе и рестораны"
    ).mapIndexed { index, (code, name) ->
        Definition(
            code,
            name,
            (
                LedgerId.parse(
                    "00000000-0000-4000-8000-" + (2001 + index).toString().padStart(12, '0')
                ) as LedgerResult.Success
                ).value
        )
    }

    internal fun matchesHint(hint: String, category: Category): Boolean {
        val normalized = hint.trim().lowercase()
        val definition = definitions.find {
            it.code == normalized || it.name.lowercase() == normalized
        } ?: return false
        val name = category.name.trim().lowercase()
        return name == definition.code || name == definition.name.lowercase()
    }

    suspend fun install(repository: CategoryRepository): LedgerResult<Unit> {
        val present = mutableSetOf<LedgerId>()
        var offset = 0
        while (true) {
            val page = when (val result = repository.list(true, 200, offset)) {
                is LedgerResult.Failure -> return result
                is LedgerResult.Success -> result.value
            }
            for (category in page) {
                definitions.filter {
                    category.id == it.id ||
                        (category.type == EntryType.EXPENSE && matchesHint(it.code, category))
                }.forEach { present.add(it.id) }
            }
            if (page.size < 200) break
            offset += page.size
        }
        for (definition in definitions) {
            if (definition.id in present) continue
            when (
                val result = repository.create(
                    definition.id,
                    EntryType.EXPENSE,
                    definition.name
                )
            ) {
                is LedgerResult.Failure -> return result
                is LedgerResult.Success -> Unit
            }
        }
        return LedgerResult.Success(Unit)
    }
}
