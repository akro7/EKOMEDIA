package com.eko.media.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.media.model.DownloadTask
import com.eko.media.model.VideoFormat
import com.eko.media.model.VideoInfo
import com.eko.media.service.DownloadService
import com.eko.media.util.VideoExtractor
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel : ViewModel() {

    // ── UI State ──────────────────────────────────────────────

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Info(val info: VideoInfo) : UiState()
        data class Error(val msg: String) : UiState()
    }

    val uiState = MutableLiveData<UiState>(UiState.Idle)
    val downloads = MutableLiveData<List<DownloadTask>>(emptyList())
    val currentUrl = MutableLiveData<String>("")

    // ── Fetch Video Info ──────────────────────────────────────

    fun fetchVideoInfo(url: String) {
        if (url.isBlank()) {
            uiState.value = UiState.Error("Please enter a URL")
            return
        }

        currentUrl.value = url.trim()
        uiState.value = UiState.Loading

        viewModelScope.launch {
            when (val result = VideoExtractor.extract(url.trim())) {
                is VideoExtractor.Result.Success -> uiState.value = UiState.Info(result.info)
                is VideoExtractor.Result.Error   -> uiState.value = UiState.Error(result.message)
            }
        }
    }

    // ── Start Download ────────────────────────────────────────

    fun startDownload(
        context: android.content.Context,
        info: VideoInfo,
        format: VideoFormat
    ) {
        val task = DownloadTask(
            id        = UUID.randomUUID().toString(),
            url       = currentUrl.value ?: return,
            format    = format,
            title     = info.title,
            thumbnail = info.thumbnail
        )

        DownloadService.activeDownloads[task.id] = task
        DownloadService.startDownload(context, task)

        // Add to local list
        val current = downloads.value?.toMutableList() ?: mutableListOf()
        current.add(0, task)
        downloads.value = current

        // Reset UI to idle
        uiState.value = UiState.Idle
    }

    // ── Poll download progress ────────────────────────────────

    fun refreshDownloads() {
        val synced = DownloadService.activeDownloads.values.sortedByDescending { it.createdAt }
        downloads.value = synced
    }

    fun reset() {
        uiState.value = UiState.Idle
        currentUrl.value = ""
    }
}
