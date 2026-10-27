package com.rokid.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.rokid.phone.adapter.MediaAdapter
import com.rokid.phone.base.BaseActivity
import com.rokid.phone.databinding.ActivityGalleryBinding
import com.rokid.phone.utils.demoString
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveV2Listener
import java.io.File

class GalleryActivity : BaseActivity<ActivityGalleryBinding>() {
    private val media = mutableListOf<File>()
    private lateinit var adapter: MediaAdapter
    private var listening = false

    private val fileListener = object : FileReceiveV2Listener {
        override fun onStart(filePath: String) {
            showProgress(demoString(R.string.demo_receiving_file))
        }

        override fun onProgressChanged(filePath: String, progress: Float) {
            showProgress(demoString(R.string.demo_receiving_progress, progress.toInt()))
        }

        override fun onComplete(filePath: String) {
            binding.receiveProgress.visibility = View.GONE
            loadMedia()
        }

        override fun onFail() {
            binding.receiveProgress.visibility = View.GONE
        }

        override fun onCancel(filePath: String) {
            binding.receiveProgress.visibility = View.GONE
        }
    }

    override fun initViewBinding(): ActivityGalleryBinding =
        ActivityGalleryBinding.inflate(layoutInflater)

    override fun onInit(savedInstanceState: Bundle?) {
        adapter = MediaAdapter(media, ::openMedia)
        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerView.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        loadMedia()
    }

    override fun onStart() {
        super.onStart()
        if (!listening) {
            PSecuritySDK.getMessageService()?.getFileOperater()?.addFileReceiveV2Listener(fileListener)
            PSecuritySDK.getMessageService()?.getBtFileOperater()?.addFileReceiveV2Listener(fileListener)
            listening = true
        }
        loadMedia()
    }

    override fun onStop() {
        if (listening) {
            PSecuritySDK.getMessageService()?.getFileOperater()?.removeFileReceiveV2Listener(fileListener)
            PSecuritySDK.getMessageService()?.getBtFileOperater()?.removeFileReceiveV2Listener(fileListener)
            listening = false
        }
        super.onStop()
    }

    private fun loadMedia() {
        val files = directMediaFiles()
        media.clear()
        media.addAll(files.sortedByDescending { it.lastModified() })
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (media.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun directMediaFiles(): List<File> {
        val roots = listOfNotNull(getExternalFilesDir(null), filesDir)
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && isMedia(it) }
                .toList()
        }.distinctBy { it.absolutePath }
    }

    private fun isMedia(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in setOf("jpg", "jpeg", "png", "webp", "mp4", "mov", "avi")
    }

    private fun openMedia(file: File) {
        val mime = if (file.extension.lowercase() in setOf("mp4", "mov", "avi")) {
            "video/*"
        } else {
            "image/*"
        }
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.demo_no_media_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProgress(message: String) {
        binding.receiveProgress.visibility = View.VISIBLE
        binding.receiveProgress.text = message
    }
}
