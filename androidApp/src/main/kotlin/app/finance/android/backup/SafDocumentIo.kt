package app.finance.android.backup

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/** Bounded provider opening; nonblocking descriptors make stalled pipe reads/writes cancellable. */
internal object SafDocumentIo {
    private fun pool(name: String) = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2),
        { work -> Thread(work, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val opener = pool("FinanceDocumentOpen")
    private val canceler = pool("FinanceDocumentCancel")
    private suspend fun open(
        resolver: ContentResolver,
        uri: Uri,
        mode: String
    ): ParcelFileDescriptor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation {
            // A non-cooperating remote provider must never block the UI cancellation handler.
            try {
                canceler.execute { signal.cancel() }
            } catch (
                _: Exception
            ) { /* bounded queue */ }
        }
        try {
            opener.execute {
                try {
                    if (!continuation.isActive) return@execute
                    val descriptor = resolver.openFileDescriptor(uri, mode, signal)
                        ?: throw IOException("Document unavailable")
                    continuation.resume(descriptor) { _, resource, _ -> resource.close() }
                } catch (_: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Document unavailable")
                        )
                    }
                }
            }
        } catch (_: Exception) {
            continuation.resumeWithException(IOException("Document provider busy"))
        }
    }
    private fun nonblocking(descriptor: ParcelFileDescriptor) {
        val fd = descriptor.fileDescriptor
        if (Build.VERSION.SDK_INT >= 30) {
            Os.fcntlInt(
                fd,
                OsConstants.F_SETFL,
                Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK
            )
        } else {
            // API29 has poll/read/write, but no public fcntlInt. With one reader,
            // readable pipes cannot lose readiness; writes stay within PIPE_BUF.
            val mode = Os.fstat(fd).st_mode
            if (!OsConstants.S_ISREG(mode) && !OsConstants.S_ISFIFO(mode)) {
                throw IOException("Unsupported document descriptor")
            }
        }
    }
    private suspend fun ready(descriptor: ParcelFileDescriptor, writing: Boolean) {
        val event = if (writing) OsConstants.POLLOUT else OsConstants.POLLIN
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = event.toShort()
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (Os.poll(arrayOf(poll), 100) > 0) return
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EINTR) throw failure
            }
        }
    }
    suspend fun read(resolver: ContentResolver, uri: Uri): ByteArray =
        open(resolver, uri, "r").use { descriptor ->
            nonblocking(descriptor)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                ready(descriptor, false)
                val count = try {
                    Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                } catch (
                    failure: ErrnoException
                ) {
                    if (failure.errno == OsConstants.EAGAIN ||
                        failure.errno == OsConstants.EINTR
                    ) {
                        continue
                    }
                    throw failure
                }
                if (count == 0) break
                if (bytes.size() + count > 8 * 1024 * 1024) throw DocumentTooLarge()
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    suspend fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) =
        open(resolver, uri, "wt").use { descriptor ->
            nonblocking(descriptor)
            var offset = 0
            while (offset < bytes.size) {
                ready(descriptor, true)
                val count = try {
                    Os.write(
                        descriptor.fileDescriptor,
                        bytes,
                        offset,
                        minOf(
                            8192,
                            bytes.size - offset
                        )
                    )
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.EAGAIN ||
                        failure.errno == OsConstants.EINTR
                    ) {
                        continue
                    }
                    throw failure
                }
                if (count <= 0) throw IOException("Document unavailable")
                offset += count
            }
        }
}
internal class DocumentTooLarge : IOException("Document exceeds limit")
