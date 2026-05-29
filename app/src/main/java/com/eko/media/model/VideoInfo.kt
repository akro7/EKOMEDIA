package com.eko.media.model

data class VideoInfo(
    val title: String,
    val thumbnail: String,
    val duration: String,
    val author: String,
    val platform: Platform,
    val formats: List<VideoFormat>
)

data class VideoFormat(
    val quality: String,       // e.g. "1080p", "720p", "480p", "360p", "audio"
    val ext: String,           // "mp4", "webm", "m4a"
    val formatId: String,
    val filesize: Long?,       // bytes, nullable
    val isAudioOnly: Boolean = false
) {
    fun displaySize(): String {
        if (filesize == null) return "? MB"
        return when {
            filesize >= 1_073_741_824 -> "%.1f GB".format(filesize / 1_073_741_824.0)
            filesize >= 1_048_576    -> "%.1f MB".format(filesize / 1_048_576.0)
            filesize >= 1_024        -> "%.0f KB".format(filesize / 1_024.0)
            else                     -> "$filesize B"
        }
    }
}

enum class Platform(val displayName: String, val color: Int) {
    YOUTUBE("YouTube", 0xFFFF0000.toInt()),
    TWITTER("Twitter/X", 0xFF1DA1F2.toInt()),
    INSTAGRAM("Instagram", 0xFFE1306C.toInt()),
    TIKTOK("TikTok", 0xFF010101.toInt()),
    FACEBOOK("Facebook", 0xFF1877F2.toInt()),
    REDDIT("Reddit", 0xFFFF4500.toInt()),
    GENERIC("Web", 0xFF00C8FF.toInt());

    companion object {
        fun detect(url: String): Platform = when {
            "youtube.com" in url || "youtu.be" in url -> YOUTUBE
            "twitter.com" in url || "x.com" in url   -> TWITTER
            "instagram.com" in url                    -> INSTAGRAM
            "tiktok.com" in url                       -> TIKTOK
            "facebook.com" in url || "fb.watch" in url -> FACEBOOK
            "reddit.com" in url || "redd.it" in url   -> REDDIT
            else                                      -> GENERIC
        }
    }
}

data class DownloadTask(
    val id: String,
    val url: String,
    val format: VideoFormat,
    val title: String,
    val thumbnail: String,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var speed: String = "",
    var localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED
}
