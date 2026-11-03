package app.finance.android.backup

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.finance.domain.backup.BackupError
import app.finance.domain.backup.BackupRepository
import app.finance.domain.backup.BackupResult
import app.finance.domain.backup.ImportPreview
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class BackupState(
    val busy: Boolean = false,
    val preview: ImportPreview? = null,
    val error: BackupError? = null,
    val completed: Boolean = false,
    val cancellable: Boolean = false
) {
    override fun toString() = "BackupState"
}
internal class BackupViewModel(
    private val repository: BackupRepository,
    private val acquire: () -> Boolean,
    private val release: (Boolean) -> Unit,
    private val beforeReplace: suspend () -> Unit = {}
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var epoch = 0L
    fun dismiss() {
        if (!state.value.busy) mutable.value = BackupState()
    }
    fun cancelIo() {
        if (!state.value.busy || !state.value.cancellable) return
        epoch++
        operation?.cancel()
        mutable.value = BackupState()
    }
    private fun io(block: suspend () -> BackupState) {
        if (state.value.busy || operation?.isCompleted == false) return
        val token = ++epoch
        mutable.update { it.copy(busy = true, error = null, completed = false, cancellable = true) }
        operation = viewModelScope.launch {
            try {
                val result = withTimeout(30_000) { withContext(Dispatchers.IO) { block() } }
                if (token == epoch) mutable.value = result
            } catch (_: TimeoutCancellationException) {
                if (token == epoch) mutable.value = BackupState(error = BackupError.STORAGE_FAILURE)
            } catch (
                cancel: CancellationException
            ) {
                throw cancel
            } catch (_: DocumentTooLarge) {
                if (token ==
                    epoch
                ) {
                    mutable.value = BackupState(error = BackupError.TOO_LARGE)
                }
            } catch (_: java.nio.charset.CharacterCodingException) {
                if (token ==
                    epoch
                ) {
                    mutable.value = BackupState(error = BackupError.INVALID_FORMAT)
                }
            } catch (_: Exception) {
                if (token ==
                    epoch
                ) {
                    mutable.value = BackupState(error = BackupError.STORAGE_FAILURE)
                }
            } finally {
                if (token ==
                    epoch
                ) {
                    mutable.update { it.copy(busy = false, cancellable = false) }
                }
            }
        }
    }
    fun export(resolver: ContentResolver, uri: Uri, csv: Boolean) {
        val preview = state.value.preview
        io {
            when (val archive = if (csv) repository.exportCsv() else repository.exportJson()) {
                is BackupResult.Failure -> BackupState(preview = preview, error = archive.error)
                is BackupResult.Success -> {
                    SafDocumentIo.write(resolver, uri, archive.value.toByteArray(Charsets.UTF_8))
                    BackupState(preview = preview, completed = true)
                }
            }
        }
    }
    fun read(resolver: ContentResolver, uri: Uri) = io {
        val bytes = SafDocumentIo.read(resolver, uri)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(
                CodingErrorAction.REPORT
            ).decode(ByteBuffer.wrap(bytes)).toString()
        when (val result = repository.previewImport(json)) {
            is BackupResult.Success -> BackupState(preview = result.value)
            is BackupResult.Failure -> BackupState(error = result.error)
        }
    }
    fun confirmReplace() {
        val preview = state.value.preview ?: return
        if (state.value.busy || operation?.isCompleted == false || !acquire()) return
        mutable.update { it.copy(busy = true, error = null, cancellable = false) }
        operation = viewModelScope.launch {
            var replaced = false
            try {
                when (
                    val result = withContext(Dispatchers.IO) {
                        beforeReplace()
                        repository.replace(preview)
                    }
                ) {
                    is BackupResult.Success -> {
                        replaced = true
                        mutable.value =
                            BackupState(busy = true, completed = true)
                    }
                    is BackupResult.Failure ->
                        mutable.value =
                            BackupState(busy = true, error = result.error)
                }
            } catch (
                cancel: CancellationException
            ) {
                throw cancel
            } catch (_: Exception) {
                mutable.value =
                    BackupState(busy = true, error = BackupError.STORAGE_FAILURE)
            } finally {
                release(replaced)
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
