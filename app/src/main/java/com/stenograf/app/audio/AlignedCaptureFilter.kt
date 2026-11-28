package com.stenograf.app.audio

/**
 * Keeps the unprocessed samples still held by a streaming denoiser. If the filter fails,
 * the ASR spool can continue at the same position using those samples and the raw input.
 */
internal class AlignedCaptureFilter(
    private val process: (ShortArray, Int) -> ShortArray,
    private val flush: () -> ShortArray,
    private val reset: () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private var pending = ShortArray(0)
    private var bypass = false

    fun push(samples: ShortArray, count: Int): ShortArray {
        if (bypass) return samples.copyOf(count)
        val before = pending.size
        pending = pending.copyOf(before + count)
        samples.copyInto(pending, before, 0, count)
        val result = attempt { process(samples, count) } ?: return takePending()
        val emitted = result.size.coerceAtMost(pending.size)
        if (pending.size - emitted > 16_000) {
            fail(IllegalStateException("Streaming audio filter held too much input"))
            return takePending()
        }
        pending = pending.copyOfRange(emitted, pending.size)
        return if (result.size == emitted) result else result.copyOf(emitted)
    }

    fun drain(): ShortArray {
        if (bypass || pending.isEmpty()) return takePending()
        val tail = attempt(flush) ?: return takePending()
        val dry = takePending()
        tail.copyInto(dry, endIndex = minOf(tail.size, dry.size))
        return dry
    }

    fun restart() {
        if (!bypass) attempt(reset)
    }

    private fun takePending(): ShortArray = pending.also { pending = ShortArray(0) }

    private fun <T> attempt(action: () -> T): T? {
        return try {
            action()
        } catch (failure: Exception) {
            fail(failure)
            null
        } catch (failure: LinkageError) {
            fail(failure)
            null
        }
    }

    private fun fail(failure: Throwable) {
        bypass = true
        onFailure(failure)
    }
}
