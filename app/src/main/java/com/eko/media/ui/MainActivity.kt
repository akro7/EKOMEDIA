package com.eko.media.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.eko.media.R
import com.eko.media.databinding.ActivityMainBinding
import com.eko.media.model.VideoInfo
import com.eko.media.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var downloadAdapter: DownloadAdapter

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (!granted) showSnack("⚠ صلاحية التخزين مطلوبة للتحميل")
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            vm.refreshDownloads()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        requestPermissions()
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun setupUI() {
        downloadAdapter = DownloadAdapter()
        b.rvDownloads.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadAdapter
        }

        b.btnFetch.setOnClickListener {
            hideKeyboard()
            val url = b.etUrl.text.toString().trim()
            vm.fetchVideoInfo(url)
        }

        b.etUrl.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                vm.fetchVideoInfo(b.etUrl.text.toString().trim())
                true
            } else false
        }

        b.btnPaste.setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                b.etUrl.setText(text)
                vm.fetchVideoInfo(text.trim())
            }
        }

        b.btnClear.setOnClickListener {
            b.etUrl.text?.clear()
            vm.reset()
        }
    }

    private fun observeViewModel() {
        vm.uiState.observe(this) { state ->
            when (state) {
                is MainViewModel.UiState.Idle    -> showIdle()
                is MainViewModel.UiState.Loading -> showLoading()
                is MainViewModel.UiState.Info    -> showVideoInfo(state.info)
                is MainViewModel.UiState.Error   -> showError(state.msg)
            }
        }

        vm.downloads.observe(this) { list ->
            downloadAdapter.submitList(list.toList())
            val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
            b.tvDownloadsHeader.visibility = visible
            b.rvDownloads.visibility = visible
            try { b.layoutDownloadsHeader.visibility = visible } catch (_: Exception) {}
        }
    }

    private fun showIdle() {
        b.cardInfo.visibility    = View.GONE
        b.cardLoading.visibility = View.GONE
        b.cardError.visibility   = View.GONE
    }

    private fun showLoading() {
        b.cardInfo.visibility    = View.GONE
        b.cardLoading.visibility = View.VISIBLE
        b.cardError.visibility   = View.GONE
    }

    private fun showVideoInfo(info: VideoInfo) {
        b.cardLoading.visibility = View.GONE
        b.cardError.visibility   = View.GONE
        b.cardInfo.visibility    = View.VISIBLE

        b.tvVideoTitle.text = info.title
        b.tvAuthor.text     = "👤 ${info.author}"
        b.tvDuration.text   = "⏱ ${info.duration}"
        b.tvPlatform.text   = info.platform.displayName
        b.tvPlatform.setBackgroundColor(info.platform.color)

        if (info.thumbnail.isNotBlank()) {
            com.bumptech.glide.Glide.with(this)
                .load(info.thumbnail)
                .placeholder(R.drawable.thumb_placeholder)
                .into(b.ivThumbnail)
        } else {
            b.ivThumbnail.setImageResource(R.drawable.thumb_placeholder)
        }

        b.btnDownload.setOnClickListener {
            showQualityDialog(info)
        }

        b.btnPlayInApp.setOnClickListener {
            val url = vm.currentUrl.value ?: return@setOnClickListener
            PlayerActivity.start(this, url, info.title, info.thumbnail)
        }
    }

    private fun showError(msg: String) {
        b.cardInfo.visibility    = View.GONE
        b.cardLoading.visibility = View.GONE
        b.cardError.visibility   = View.VISIBLE
        b.tvError.text           = msg
        b.btnRetry.setOnClickListener {
            vm.fetchVideoInfo(b.etUrl.text.toString())
        }
    }

    private fun showQualityDialog(info: VideoInfo) {
        val formats = info.formats
        val labels  = formats.map { f ->
            if (f.isAudioOnly) "🎵 ${f.quality} (${f.ext.uppercase()})"
            else "🎬 ${f.quality} · ${f.ext.uppercase()} · ${f.displaySize()}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this, R.style.EkoDialog)
            .setTitle("اختر الجودة")
            .setItems(labels) { _, i ->
                vm.startDownload(this, info, formats[i])
                showSnack("⬇ بدأ التحميل: ${formats[i].quality}")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) permLauncher.launch(notGranted.toTypedArray())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(b.root.windowToken, 0)
    }

    private fun showSnack(msg: String) {
        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.eko_accent))
            .show()
    }
}
