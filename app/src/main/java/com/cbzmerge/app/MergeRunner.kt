package com.cbzmerge.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MergeStatus {
    data object Idle : MergeStatus
    data class Working(val progress: Float, val label: String) : MergeStatus
    data class Done(val files: List<Written>, val oldVersions: List<DocEntry>) : MergeStatus
    data class Failed(val message: String) : MergeStatus
}

/** Everything needed to run a merge, captured when the user taps Merge. */
data class MergeRequest(
    val outFolder: Uri,
    val outputs: List<OutputPlan>,
    val cover: CoverSource?,
    val quality: Quality,
    val format: ImageFormat
)

/**
 * Runs merges outside of any screen, so they keep going when the app is in the background.
 * [MergeService] keeps the process alive and shows the progress in a notification.
 */
object MergeRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<MergeStatus>(MergeStatus.Idle)
    val status: StateFlow<MergeStatus> = _status.asStateFlow()

    val isRunning get() = job?.isActive == true

    fun start(context: Context, request: MergeRequest) {
        if (isRunning) return
        val app = context.applicationContext
        _status.value = MergeStatus.Working(0f, "Starting…")
        MergeService.start(app)
        job = scope.launch {
            _status.value = try {
                run(app, request)
            } catch (e: CancellationException) {
                MergeStatus.Failed("Merge cancelled.")
            } catch (e: OutOfMemoryError) {
                MergeStatus.Failed("An image is too large for the phone's memory.")
            } catch (e: Exception) {
                MergeStatus.Failed(e.message ?: "Merge failed.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Clears the "old versions" question once the user answered it. */
    fun clearOldVersions() {
        (_status.value as? MergeStatus.Done)?.let { _status.value = it.copy(oldVersions = emptyList()) }
    }

    fun reset() {
        if (!isRunning) _status.value = MergeStatus.Idle
    }

    private suspend fun run(context: Context, request: MergeRequest): MergeStatus {
        val outputs = request.outputs
        val totalPages = outputs.sumOf { it.pages }.coerceAtLeast(1)
        val cover = request.cover?.let { Covers.load(context, it) }
        var done = 0
        val written = outputs.mapIndexed { i, output ->
            Merger.merge(context, request.outFolder, output.fileName, output.chapters, cover, request.quality, request.format) { ci ->
                done++
                val file = if (outputs.size > 1) "File ${i + 1} of ${outputs.size} · " else ""
                _status.value = MergeStatus.Working(done.toFloat() / totalPages, "${file}Chapter ${ci + 1} of ${output.chapters.size}")
            }
        }
        val old = Merger.findOldVersions(context, request.outFolder, written)
        return MergeStatus.Done(written, old)
    }
}
