package com.eko.media.util

import com.eko.media.model.Platform
import com.eko.media.model.VideoFormat
import com.eko.media.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * EKO MEDIA - Video Extractor powered by NewPipe Extractor
 *
 * FIXES applied:
 *
 * FIX 1 — DeliveryMethod filter in extractWithNewPipe:
 *   YouTube videoStreams / videoOnlyStreams / audioStreams contain both
 *   PROGRESSIVE_HTTP and DASH entries. The DASH entries have manifest URLs,
 *   not direct file URLs, so storing them in VideoFormat.formatId causes the
 *   DownloadEngine to download an XML manifest instead of the actual media.
 *   We now only expose PROGRESSIVE_HTTP streams in the format list,
 *   matching NewPipe's own download dialog (DownloadDialog.java) behaviour.
 *
 * FIX 2 — Null content guard:
 *   stream.content can be null. All forEach blocks now skip null/blank content.
 */
object VideoExtractor {

    sealed class Result {
        data class Success(val info: VideoInfo) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun extract(url: String): Result = withContext(Dispatchers.IO) {
        val platform = Platform.detect(url)
        return@withContext when (platform) {
            Platform.TIKTOK -> extractTikTok(url, platform)
            else            -> extractWithNewPipe(url, platform)
        }
    }

    private fun extractWithNewPipe(url: String, platform: Platform): Result {
        return try {
            val streamInfo = StreamInfo.getInfo(url)

            val title    = streamInfo.name ?: "Video"
            val author   = streamInfo.uploaderName ?: platform.displayName
            val duration = if (streamInfo.duration > 0) formatDuration(streamInfo.duration) else "–"
            val thumb    = streamInfo.thumbnails.firstOrNull()?.url ?: ""

            val formats = mutableListOf<VideoFormat>()

            // ── Muxed video+audio (videoStreams) — PROGRESSIVE_HTTP only ──
            // FIX 1: filter by DeliveryMethod so we only store direct HTTP URLs
            streamInfo.videoStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.height > 0 }
                .filter { !it.content.isNullOrBlank() }
                .sortedByDescending { it.height }
                .take(5)
                .forEach { vs ->
                    val dlUrl = vs.content ?: return@forEach
                    formats.add(VideoFormat(
                        quality     = "${vs.height}p",
                        ext         = vs.format?.suffix ?: "mp4",
                        formatId    = dlUrl,
                        filesize    = null,
                        isAudioOnly = false
                    ))
                }

            // ── Video-only streams — PROGRESSIVE_HTTP only ─────────────
            if (formats.isEmpty()) {
                streamInfo.videoOnlyStreams
                    .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .filter { it.height > 0 }
                    .filter { !it.content.isNullOrBlank() }
                    .sortedByDescending { it.height }
                    .take(3)
                    .forEach { vs ->
                        val dlUrl = vs.content ?: return@forEach
                        formats.add(VideoFormat(
                            quality     = "${vs.height}p",
                            ext         = vs.format?.suffix ?: "mp4",
                            formatId    = dlUrl,
                            filesize    = null,
                            isAudioOnly = false
                        ))
                    }
            }

            // ── Audio streams — PROGRESSIVE_HTTP only ─────────────────
            streamInfo.audioStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { !it.content.isNullOrBlank() }
                .sortedByDescending { it.averageBitrate }
                .take(2)
                .forEach { aus ->
                    val dlUrl = aus.content ?: return@forEach
                    val qualityLabel = when {
                        aus.averageBitrate > 0 -> "${aus.averageBitrate}kbps"
                        else                   -> "Audio"
                    }
                    formats.add(VideoFormat(
                        quality     = qualityLabel,
                        ext         = aus.format?.suffix ?: "m4a",
                        formatId    = dlUrl,
                        filesize    = null,
                        isAudioOnly = true
                    ))
                }

            // ── Fallback: HLS / DASH manifest ─────────────────────────
            if (formats.isEmpty()) {
                val hlsUrl  = streamInfo.hlsUrl
                val dashUrl = streamInfo.dashMpdUrl
                when {
                    !hlsUrl.isNullOrBlank()  -> formats.add(VideoFormat("Best Quality", "m3u8", hlsUrl,  null, false))
                    !dashUrl.isNullOrBlank() -> formats.add(VideoFormat("Best Quality", "mpd",  dashUrl, null, false))
                    else -> return Result.Error("لا توجد صيغ متاحة. المنصة قد لا تكون مدعومة.")
                }
            }

            Result.Success(VideoInfo(
                title     = title,
                thumbnail = thumb,
                duration  = duration,
                author    = author,
                platform  = platform,
                formats   = formats
            ))

        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: e.javaClass.simpleName
            Result.Error("خطأ في استخراج الفيديو: $errMsg")
        }
    }

    private fun extractTikTok(url: String, platform: Platform): Result {
        return try {
            val httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val apiUrl  = "https://www.tikwm.com/api/?url=$encoded&hd=1"
            val req     = okhttp3.Request.Builder().url(apiUrl).build()
            val resp    = httpClient.newCall(req).execute()
            val json    = org.json.JSONObject(resp.body?.string() ?: "{}")

            if (json.optInt("code", -1) != 0) {
                return extractWithNewPipe(url, platform)
            }

            val data     = json.getJSONObject("data")
            val title    = data.optString("title", "TikTok Video")
            val thumb    = data.optString("cover", "")
            val author   = data.optJSONObject("author")?.optString("nickname", "TikTok") ?: "TikTok"
            val hdUrl    = data.optString("hdplay", "")
            val sdUrl    = data.optString("play", "")
            val audioUrl = data.optString("music", "")
            val wmUrl    = data.optString("wmplay", "")

            val formats = mutableListOf<VideoFormat>()
            if (hdUrl.isNotBlank())    formats.add(VideoFormat("HD بدون علامة مائية", "mp4", hdUrl,    null, false))
            if (sdUrl.isNotBlank())    formats.add(VideoFormat("SD بدون علامة مائية", "mp4", sdUrl,    null, false))
            if (wmUrl.isNotBlank())    formats.add(VideoFormat("مع علامة مائية",       "mp4", wmUrl,    null, false))
            if (audioUrl.isNotBlank()) formats.add(VideoFormat("صوت فقط",              "mp3", audioUrl, null, true))

            if (formats.isEmpty()) return extractWithNewPipe(url, platform)

            Result.Success(VideoInfo(title, thumb, "–", author, Platform.TIKTOK, formats))
        } catch (e: Exception) {
            extractWithNewPipe(url, platform)
        }
    }

    suspend fun buildDownloadUrl(sourceUrl: String, format: VideoFormat): String? =
        withContext(Dispatchers.IO) {
            if (format.formatId.startsWith("http")) return@withContext format.formatId
            null
        }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
