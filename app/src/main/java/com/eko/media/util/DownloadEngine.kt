package com.eko.media.util

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download engine rebuilt using the same approach as NewPipe's DownloadMission:
 *  - HttpURLConnection (not OkHttp) — avoids OkHttp's header stripping
 *  - Same User-Agent as DownloaderImpl (browser UA, required by YouTube CDN)
 *  - Range header on every request (required by YouTube to serve the file)
 *  - 64KB buffer (same as NewPipe's BUFFER_SIZE)
 *  - 30s connect timeout, no read timeout (same as NewPipe)
 */
object DownloadEngine {

    // ✅ Exact same UA as NewPipe's DownloaderImpl.USER_AGENT
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private const val BUFFER_SIZE = 64 * 1024   // 64 KB — same as NewPipe

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val percent: Int,
        val speedBps: Long,
        val done: Boolean = false,
        val filePath: String? = null,
        val error: String? = null
    )

    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: suspend (Progress) -> Unit
    ): String? = withContext(Dispatchers.IO) {

        val dir  = getDownloadDir(context)
        val file = File(dir, sanitizeFileName(fileName))

        try {
            // ── Open connection exactly like NewPipe's openConnection() ──
            val conn = openConnection(url, rangeStart = 0, rangeEnd = -1)
            conn.connect()

            val statusCode = conn.responseCode

            // YouTube returns 206 for Range requests, 200 for full file
            if (statusCode != 200 && statusCode != 206) {
                onProgress(Progress(0, 0, 0, 0, error = "HTTP $statusCode"))
                conn.disconnect()
                return@withContext null
            }

            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L
            var lastTime  = System.currentTimeMillis()
            var lastBytes = 0L

            FileOutputStream(file).use { fos ->
                val input: InputStream = conn.inputStream
                val buf = ByteArray(BUFFER_SIZE)
                var read: Int

                while (isActive) {
                    read = input.read(buf, 0, buf.size)
                    if (read == -1) break

                    fos.write(buf, 0, read)
                    downloadedBytes += read

                    val now     = System.currentTimeMillis()
                    val elapsed = now - lastTime

                    if (elapsed >= 300) {
                        val speed   = ((downloadedBytes - lastBytes) * 1000L) / elapsed
                        val percent = if (totalBytes > 0)
                            (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
                        else 0

                        onProgress(Progress(
                            downloaded = downloadedBytes,
                            total      = totalBytes,
                            percent    = percent,
                            speedBps   = speed
                        ))

                        lastTime  = now
                        lastBytes = downloadedBytes
                    }
                }

                input.close()
            }

            conn.disconnect()

            onProgress(Progress(
                downloaded = downloadedBytes,
                total      = downloadedBytes,
                percent    = 100,
                speedBps   = 0,
                done       = true,
                filePath   = file.absolutePath
            ))

            file.absolutePath

        } catch (e: Exception) {
            file.delete()
            onProgress(Progress(0, 0, 0, 0, error = e.message ?: "Download failed"))
            null
        }
    }

    /**
     * Opens HttpURLConnection with the exact same headers NewPipe uses in
     * DownloadMission.openConnection():
     *   - User-Agent     : browser UA (YouTube CDN requires it)
     *   - Accept         : star/star
     *   - Accept-Encoding: star (let server choose)
     *   - Range          : bytes=START- (required for YouTube to serve bytes)
     */
    private fun openConnection(url: String, rangeStart: Long, rangeEnd: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "*")
        conn.connectTimeout = 30_000   // 30s — same as NewPipe
        // no read timeout (readTimeout = 0) so large files don't time out

        if (rangeStart >= 0) {
            val rangeHeader = if (rangeEnd > 0) "bytes=$rangeStart-$rangeEnd"
                              else              "bytes=$rangeStart-"
            conn.setRequestProperty("Range", rangeHeader)
        }

        return conn
    }

    fun getDownloadDir(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "EkoMedia"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun formatSpeed(bps: Long): String = when {
        bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576.0)
        bps >= 1_024     -> "%.0f KB/s".format(bps / 1_024.0)
        else             -> "$bps B/s"
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(200)
}
