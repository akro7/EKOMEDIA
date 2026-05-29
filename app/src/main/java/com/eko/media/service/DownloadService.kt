package com.eko.media.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eko.media.EkoDownloader
import com.eko.media.EkoMediaApp
import com.eko.media.R
import com.eko.media.model.DownloadStatus
import com.eko.media.model.DownloadTask
import com.eko.media.ui.MainActivity
import com.eko.media.util.DownloadEngine
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }

    companion object {
        const val ACTION_DOWNLOAD = "eko.action.DOWNLOAD"
        const val NOTIF_ID = 1001

        val activeDownloads = mutableMapOf<String, DownloadTask>()

        fun startDownload(context: Context, task: DownloadTask) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra("task_id",    task.id)
                putExtra("page_url",   task.url)
                putExtra("format_ext", task.format.ext)
                putExtra("quality",    task.format.quality)
                putExtra("title",      task.title)
                putExtra("audio_only", task.format.isAudioOnly)
                putExtra("target_height", extractHeight(task.format.quality))
            }
            context.startForegroundService(intent)
        }

        private fun extractHeight(quality: String): Int =
            Regex("(\\d+)").find(quality)?.value?.toIntOrNull() ?: 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("EKO Engine ready", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) handleDownload(intent)
        return START_STICKY
    }

    private fun handleDownload(intent: Intent) {
        val taskId       = intent.getStringExtra("task_id")    ?: return
        val pageUrl      = intent.getStringExtra("page_url")   ?: return
        val ext          = intent.getStringExtra("format_ext") ?: "mp4"
        val title        = intent.getStringExtra("title")      ?: "video"
        val audioOnly    = intent.getBooleanExtra("audio_only", false)
        val targetHeight = intent.getIntExtra("target_height", 0)

        scope.launch {
            val task = activeDownloads[taskId] ?: return@launch
            task.status = DownloadStatus.DOWNLOADING
            notify("⬇ جاري التحضير: $title", 0)

            // ── Re-fetch fresh stream URL (YouTube signed URLs expire fast) ──
            val directUrl = withContext(Dispatchers.IO) {
                runCatching { resolveFreshUrl(pageUrl, audioOnly, targetHeight) }.getOrNull()
            }

            if (directUrl == null) {
                task.status = DownloadStatus.FAILED
                notify("❌ فشل جلب رابط التحميل", 0)
                stopSelf()
                return@launch
            }

            val safeName = title.replace(Regex("[^\\w\\s.\\-]"), "_").take(80)
            val fileName = "$safeName.$ext"

            DownloadEngine.download(
                context    = this@DownloadService,
                url        = directUrl,
                fileName   = fileName,
                onProgress = { prog ->
                    task.progress = prog.percent
                    task.speed    = if (prog.speedBps > 0) DownloadEngine.formatSpeed(prog.speedBps) else ""

                    when {
                        prog.done -> {
                            task.status    = DownloadStatus.COMPLETED
                            task.localPath = prog.filePath
                            notify("✅ تم الحفظ: $title", 100)
                            stopSelf()
                        }
                        prog.error != null -> {
                            task.status = DownloadStatus.FAILED
                            notify("❌ خطأ: ${prog.error}", 0)
                            stopSelf()
                        }
                        else -> {
                            notify("⬇ $title — ${prog.percent}%  ${task.speed}", prog.percent)
                        }
                    }
                }
            )
        }
    }

    /**
     * Re-extracts a FRESH direct stream URL from the page URL.
     *
     * FIXES applied (mirroring NewPipe's DownloadMissionRecover.resolveStream):
     *
     * FIX 1 — NewPipe init guard:
     *   The Service may run in a separate OS process after the app is killed.
     *   EkoMediaApp.onCreate() is not guaranteed to run before onStartCommand().
     *   We call ensureNewPipeInit() before any extractor call.
     *
     * FIX 2 — DeliveryMethod filter:
     *   YouTube returns both PROGRESSIVE_HTTP and DASH streams in videoStreams /
     *   videoOnlyStreams. Passing a DASH manifest URL to DownloadEngine (which
     *   uses a plain HttpURLConnection) downloads the XML manifest, not the
     *   actual video. We now filter to PROGRESSIVE_HTTP only, exactly as
     *   NewPipe's DownloadMissionRecover.resolveStream() does.
     *
     * FIX 3 — null content guard:
     *   stream.content can be null for streams whose URL couldn't be resolved.
     *   Added explicit null/blank checks before returning.
     */
    private fun resolveFreshUrl(pageUrl: String, audioOnly: Boolean, targetHeight: Int): String? {
        ensureNewPipeInit()

        val info = StreamInfo.getInfo(pageUrl)

        return when {
            audioOnly -> {
                info.audioStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .filter { !it.content.isNullOrBlank() }
                    .sortedByDescending { it.averageBitrate }
                    .firstOrNull()?.content
            }
            else -> {
                // 1. Muxed video+audio (videoStreams), PROGRESSIVE_HTTP only
                val muxed = info.videoStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .filter { !it.content.isNullOrBlank() }
                    .sortedByDescending { it.height }
                    .let { list ->
                        if (targetHeight > 0)
                            list.firstOrNull { it.height == targetHeight } ?: list.firstOrNull()
                        else
                            list.firstOrNull()
                    }
                if (muxed != null) return muxed.content

                // 2. Video-only streams (PROGRESSIVE_HTTP) — no audio track
                val videoOnly = info.videoOnlyStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .filter { !it.content.isNullOrBlank() }
                    .sortedByDescending { it.height }
                    .let { list ->
                        if (targetHeight > 0)
                            list.firstOrNull { it.height == targetHeight } ?: list.firstOrNull()
                        else
                            list.firstOrNull()
                    }
                if (videoOnly != null) return videoOnly.content

                // 3. HLS manifest as last resort
                info.hlsUrl?.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * FIX 1 (detail): Ensures NewPipe is initialised in this process.
     * Uses EkoDownloader singleton — same downloader the Application used.
     * If the singleton is gone (process was killed), re-creates it.
     */
    private fun ensureNewPipeInit() {
        try {
            // If this throws, NewPipe is not initialised in this process
            NewPipe.getStaticServiceByUrl("https://www.youtube.com/watch?v=test")
        } catch (_: Exception) {
            val downloader = try {
                EkoDownloader.getInstance()
            } catch (_: Exception) {
                EkoDownloader.init(OkHttpClient.Builder())
            }
            NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT)
        }
    }

    private fun notify(text: String, progress: Int) {
        notifManager.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, EkoMediaApp.CHANNEL_DOWNLOAD)
            .setContentTitle("EKO MEDIA")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setContentIntent(pi)
            .setOngoing(progress in 1..99)
            .apply { if (progress in 1..99) setProgress(100, progress, false) }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
