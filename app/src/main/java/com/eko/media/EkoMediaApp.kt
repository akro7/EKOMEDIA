package com.eko.media

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class EkoMediaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize NewPipe with Localization + ContentCountry
        val downloader = EkoDownloader.init(OkHttpClient.Builder())
        NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT)
        newPipeInitialized = true

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                "EKO Download Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active download progress"
            }
            val notifManager = getSystemService(NotificationManager::class.java)
            notifManager.createNotificationChannel(downloadChannel)
        }
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "eko_download_channel"

        /**
         * True once NewPipe.init() has been called in this process.
         * DownloadService checks this flag before calling extractor methods,
         * because the Service may start in a fresh process where onCreate()
         * has not yet run.
         */
        @Volatile
        var newPipeInitialized: Boolean = false
            private set
    }
}

/**
 * HTTP Downloader for NewPipe Extractor
 */
class EkoDownloader private constructor(builder: OkHttpClient.Builder) : Downloader() {

    private val client: OkHttpClient = builder
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                )
                .build()
            chain.proceed(req)
        }
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url        = request.url()
        val headers    = request.headers()
        val body       = request.dataToSend()

        var requestBuilder = okhttp3.Request.Builder().url(url)

        headers.forEach { (key, values) ->
            values.forEach { value -> requestBuilder = requestBuilder.addHeader(key, value) }
        }

        val httpRequest = when (httpMethod) {
            "GET"  -> requestBuilder.get().build()
            "POST" -> {
                val bodyContent = body?.toRequestBody() ?: "".toRequestBody()
                requestBuilder.post(bodyContent).build()
            }
            "HEAD" -> requestBuilder.head().build()
            else   -> requestBuilder.get().build()
        }

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: ""
        val latestUrl = response.request.url.toString()

        val responseHeaders = mutableMapOf<String, MutableList<String>>()
        response.headers.forEach { (name, value) ->
            responseHeaders.getOrPut(name) { mutableListOf() }.add(value)
        }

        return Response(
            response.code,
            response.message,
            responseHeaders,
            responseBody,
            latestUrl
        )
    }

    companion object {
        private var instance: EkoDownloader? = null

        fun init(builder: OkHttpClient.Builder?): EkoDownloader {
            instance = EkoDownloader(builder ?: OkHttpClient.Builder())
            return instance!!
        }

        fun getInstance(): EkoDownloader {
            return instance ?: throw IllegalStateException(
                "EkoDownloader not initialized. Call init() first."
            )
        }
    }
}
