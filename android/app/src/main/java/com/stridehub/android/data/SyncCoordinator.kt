package com.stridehub.android.data

import kotlinx.coroutines.CancellationException

/** Both network operations run independently; widget refresh follows every outcome. */
class SyncCoordinator(
    private val upload: suspend () -> UploadResult,
    private val download: suspend () -> UploadResult,
    private val refreshWidget: suspend () -> Unit,
    private val uploadFailed: suspend () -> Unit,
) {
    suspend fun run(): UploadResult {
        try {
            val health = try { upload() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { uploadFailed(); UploadResult.NEEDS_ATTENTION }
            val garmin = download()
            return when {
                health == UploadResult.RETRY || garmin == UploadResult.RETRY -> UploadResult.RETRY
                health == UploadResult.NEEDS_ATTENTION || garmin == UploadResult.NEEDS_ATTENTION -> UploadResult.NEEDS_ATTENTION
                health == UploadResult.NOT_CONFIGURED && garmin == UploadResult.NOT_CONFIGURED -> UploadResult.NOT_CONFIGURED
                else -> UploadResult.COMPLETE
            }
        } finally { refreshWidget() }
    }
}
