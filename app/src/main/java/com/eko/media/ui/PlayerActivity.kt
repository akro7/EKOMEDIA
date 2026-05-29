package com.eko.media.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.eko.media.databinding.ActivityPlayerBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    companion object {
        private const val EXTRA_URL       = "player_url"
        private const val EXTRA_TITLE     = "player_title"
        private const val EXTRA_THUMBNAIL = "player_thumb"

        fun start(context: Context, url: String, title: String, thumbnail: String) {
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL,       url)
                putExtra(EXTRA_TITLE,     title)
                putExtra(EXTRA_THUMBNAIL, thumbnail)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val sourceUrl = intent.getStringExtra(EXTRA_URL)       ?: return
        val title     = intent.getStringExtra(EXTRA_TITLE)     ?: "Video"
        val thumb     = intent.getStringExtra(EXTRA_THUMBNAIL) ?: ""

        b.tvPlayerTitle.text = title
        b.progressBar.visibility = View.VISIBLE
        b.tvError.visibility     = View.GONE

        if (thumb.isNotBlank()) {
            Glide.with(this).load(thumb).into(b.ivPlayerThumb)
            b.ivPlayerThumb.visibility = View.VISIBLE
        }

        b.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            resolveAndPlay(sourceUrl)
        }
    }

    // ── Resolve stream URLs then hand off to ExoPlayer ───────
    private suspend fun resolveAndPlay(pageUrl: String) {
        val result = withContext(Dispatchers.IO) { runCatching { resolveStreams(pageUrl) } }

        result.fold(
            onSuccess = { (videoUrl, audioUrl) ->
                if (videoUrl != null) {
                    setupExoPlayer(videoUrl, audioUrl)
                } else {
                    showError("لا توجد صيغة قابلة للتشغيل.\nاستخدم زر التحميل بدلاً من ذلك.")
                }
            },
            onFailure = { e ->
                showError("فشل تحميل الفيديو:\n${e.localizedMessage ?: "خطأ غير معروف"}")
            }
        )
    }

    /**
     * Uses NewPipe extractor to get the best video URL + matching audio URL.
     * YouTube returns adaptive (video-only + audio-only) streams that must be merged.
     * Returns Pair(videoUrl, audioUrl?) — audioUrl is null for muxed streams.
     */
    private fun resolveStreams(pageUrl: String): Pair<String?, String?> {
        val info = StreamInfo.getInfo(pageUrl)

        // ── 1. Try muxed (video+audio combined) streams first ──
        val muxed = info.videoStreams
            .filter { !it.content.isNullOrBlank() && it.height > 0 }
            .sortedByDescending { it.height }
            .firstOrNull()

        if (muxed != null) {
            return Pair(muxed.content, null)
        }

        // ── 2. Adaptive: pick best video-only + best audio ─────
        val videoOnly = info.videoOnlyStreams
            .filter { !it.content.isNullOrBlank() && it.height > 0 }
            .sortedByDescending { it.height }
            .firstOrNull()

        val audio = info.audioStreams
            .filter { !it.content.isNullOrBlank() }
            .sortedByDescending { it.averageBitrate }
            .firstOrNull()

        if (videoOnly != null && audio != null) {
            return Pair(videoOnly.content, audio.content)
        }

        // ── 3. Fallback: HLS manifest (works with ExoPlayer directly) ──
        val hls = info.hlsUrl
        if (!hls.isNullOrBlank()) return Pair(hls, null)

        return Pair(null, null)
    }

    // ── Build ExoPlayer with optional MergingMediaSource ─────
    private fun setupExoPlayer(videoUrl: String, audioUrl: String?) {
        // User-Agent matching our EkoDownloader
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .setAllowCrossProtocolRedirects(true)

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))

        val mediaSource = if (audioUrl != null) {
            // ✅ Merge separate video + audio streams (YouTube adaptive)
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))
            MergingMediaSource(true, videoSource, audioSource)
        } else {
            videoSource
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            b.exoPlayerView.player = exo
            exo.setMediaSource(mediaSource)
            exo.prepare()

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            b.progressBar.visibility   = View.GONE
                            b.ivPlayerThumb.visibility = View.GONE
                            exo.play()
                        }
                        Player.STATE_BUFFERING -> b.progressBar.visibility = View.VISIBLE
                        Player.STATE_ENDED     -> b.btnReplay.visibility = View.VISIBLE
                        else -> {}
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    b.progressBar.visibility   = View.GONE
                    b.ivPlayerThumb.visibility = View.GONE
                    showError("خطأ في التشغيل\nجرب التحميل بدلاً من ذلك.")
                }
            })
        }

        b.btnReplay.setOnClickListener {
            b.btnReplay.visibility = View.GONE
            player?.seekTo(0)
            player?.play()
        }
    }

    private fun showError(msg: String) {
        b.progressBar.visibility = View.GONE
        b.tvError.visibility     = View.VISIBLE
        b.tvError.text           = msg
    }

    override fun onPause()   { super.onPause();   player?.pause() }
    override fun onResume()  { super.onResume();  player?.play()  }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
