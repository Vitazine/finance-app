package app.finance.domain.ai

import app.finance.domain.ledger.LedgerId
import app.finance.domain.ledger.LedgerResult
import app.finance.domain.ledger.ManualEntry

class AiConfirmation(val transactionId: LedgerId, val replayed: Boolean) {
    override fun toString() = "AiConfirmation"
}
interface AiConfirmationRepository {
    /** Call only after explicit human confirmation of this exact edited entry. */
    suspend fun confirm(
        draftId: LedgerId,
        index: Int,
        entry: ManualEntry
    ): LedgerResult<AiConfirmation>
}
