package com.eko.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.eko.media.R
import com.eko.media.databinding.ItemDownloadBinding
import com.eko.media.model.DownloadStatus
import com.eko.media.model.DownloadTask

class DownloadAdapter :
    ListAdapter<DownloadTask, DownloadAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadTask>() {
            override fun areItemsTheSame(a: DownloadTask, b: DownloadTask) = a.id == b.id
            override fun areContentsTheSame(a: DownloadTask, b: DownloadTask) =
                a.status == b.status && a.progress == b.progress && a.speed == b.speed
        }
    }

    inner class VH(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = getItem(position)
        val ctx  = holder.b.root.context

        holder.b.tvDownloadTitle.text   = task.title
        holder.b.tvDownloadQuality.text = "${task.format.quality} · ${task.format.ext.uppercase()}"
        holder.b.progressBar.progress   = task.progress

        // Thumbnail
        if (task.thumbnail.isNotBlank()) {
            Glide.with(ctx).load(task.thumbnail)
                .placeholder(R.drawable.thumb_placeholder)
                .into(holder.b.ivThumb)
        }

        // Status
        when (task.status) {
            DownloadStatus.QUEUED      -> {
                holder.b.tvStatus.text = "⏳ Queued"
                holder.b.tvSpeed.text  = ""
            }
            DownloadStatus.DOWNLOADING -> {
                holder.b.tvStatus.text = "⬇ ${task.progress}%"
                holder.b.tvSpeed.text  = task.speed
            }
            DownloadStatus.COMPLETED   -> {
                holder.b.tvStatus.text = "✅ Done"
                holder.b.tvSpeed.text  = task.localPath?.substringAfterLast("/") ?: ""
                holder.b.progressBar.progress = 100
            }
            DownloadStatus.FAILED      -> {
                holder.b.tvStatus.text = "❌ Failed"
                holder.b.tvSpeed.text  = ""
            }
            DownloadStatus.PAUSED      -> {
                holder.b.tvStatus.text = "⏸ Paused"
                holder.b.tvSpeed.text  = ""
            }
        }
    }
}
