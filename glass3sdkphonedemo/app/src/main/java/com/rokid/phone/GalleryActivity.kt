package com.rokid.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.adapter.MediaAdapter
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.FileReceiveV2Listener
import java.io.File
import kotlin.math.roundToInt

class GalleryActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaAdapter
    private lateinit var receiveProgressContainer: View
    private lateinit var receiveProgressText: TextView
    private lateinit var receiveProgressBar: ProgressBar
    private val mediaList = mutableListOf<File>()
    private val mediaPathSet = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideReceiveProgressRunnable = Runnable {
        receiveProgressContainer.visibility = View.GONE
    }
    private var fileReceiveListenerRegistered = false

    private val fileReceiveListener = object : FileReceiveV2Listener {
        override fun onStart(filePath: String) {
            runOnUiThread {
                showReceiveProgress(filePath, 0f, "正在接收")
            }
        }

        override fun onProgressChanged(filePath: String, progress: Float) {
            runOnUiThread {
                showReceiveProgress(filePath, progress, "正在接收")
            }
        }

        override fun onComplete(filePath: String) {
            val file = File(filePath)
            runOnUiThread {
                showReceiveProgress(filePath, 100f, "接收完成")
                if (file.exists() && isImageOrVideo(file)) {
                    addOrUpdateMedia(file)
                }
                hideReceiveProgressDelayed()
            }
        }

        override fun onFail() {
            runOnUiThread {
                showReceiveProgress("", 0f, "接收失败")
                hideReceiveProgressDelayed()
            }
        }

        override fun onCancel(filePath: String) {
            runOnUiThread {
                showReceiveProgress(filePath, 0f, "接收已取消")
                hideReceiveProgressDelayed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.recyclerView)
        receiveProgressContainer = findViewById(R.id.receiveProgressContainer)
        receiveProgressText = findViewById(R.id.receiveProgressText)
        receiveProgressBar = findViewById(R.id.receiveProgressBar)

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = MediaAdapter(mediaList) { file ->
            if (file.extension.lowercase() in listOf("mp4", "avi", "mov")) {
                val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)  // 重要：授予临时读取权限
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // 如果需要的话
                }
                // 检查是否有应用可以处理这个 Intent
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    // 没有找到可以处理视频的应用
                    Toast.makeText(this, "没有找到可以播放视频的应用", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 打开图片预览
                val imagePaths = mediaList
                    .filter { isImage(it) }
                    .map { it.absolutePath }
                    .toCollection(ArrayList())
                val intent = Intent(this, ImagePreviewActivity::class.java)
                intent.putExtra("imagePath", file.absolutePath)
                intent.putStringArrayListExtra("imagePaths", imagePaths)
                intent.putExtra("imageIndex", imagePaths.indexOf(file.absolutePath).coerceAtLeast(0))
                startActivity(intent)
            }
        }
        recyclerView.adapter = adapter
        loadMedia()
    }

    override fun onStart() {
        super.onStart()
        registerFileReceiveListener()
        loadMedia()
    }

    override fun onStop() {
        super.onStop()
        unregisterFileReceiveListener()
        mainHandler.removeCallbacks(hideReceiveProgressRunnable)
        receiveProgressContainer.visibility = View.GONE
    }

    private fun loadMedia() {
        mediaList.clear()
        mediaPathSet.clear()

        getMediaDirectories().forEach { dir ->
            if (dir.exists()) {
                scanFiles(dir)
            }
        }

        mediaList.sortByDescending { it.lastModified() }
        adapter.notifyDataSetChanged()
    }

    private fun scanFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanFiles(file)
            } else {
                if (isImageOrVideo(file)) {
                    addMediaIfAbsent(file)
                }
            }
        }
    }

    private fun addOrUpdateMedia(file: File) {
        val path = file.absolutePath
        val oldIndex = mediaList.indexOfFirst { it.absolutePath == path }
        if (oldIndex >= 0) {
            mediaList.removeAt(oldIndex)
        } else {
            mediaPathSet.add(path)
        }
        mediaList.add(file)
        mediaList.sortByDescending { it.lastModified() }
        adapter.notifyDataSetChanged()
    }

    private fun addMediaIfAbsent(file: File) {
        if (mediaPathSet.add(file.absolutePath)) {
            mediaList.add(file)
        }
    }

    private fun getMediaDirectories(): List<File> {
        return listOfNotNull(
            getExternalFilesDir(null),
            filesDir
        ).distinctBy { it.absolutePath }
    }

    private fun showReceiveProgress(filePath: String, progress: Float, status: String) {
        val progressValue = progress.roundToInt().coerceIn(0, 100)
        val fileName = File(filePath).name
        val displayName = if (fileName.isBlank()) "" else " $fileName"

        mainHandler.removeCallbacks(hideReceiveProgressRunnable)
        receiveProgressContainer.visibility = View.VISIBLE
        receiveProgressBar.progress = progressValue
        receiveProgressText.text = "$status$displayName $progressValue%"
    }

    private fun hideReceiveProgressDelayed() {
        mainHandler.removeCallbacks(hideReceiveProgressRunnable)
        mainHandler.postDelayed(hideReceiveProgressRunnable, 1500)
    }

    private fun registerFileReceiveListener() {
        if (fileReceiveListenerRegistered) {
            return
        }
        PSecuritySDK.getMessageService()?.getFileOperater()?.addFileReceiveV2Listener(fileReceiveListener)
        PSecuritySDK.getMessageService()?.getBtFileOperater()?.addFileReceiveV2Listener(fileReceiveListener)
        fileReceiveListenerRegistered = true
    }

    private fun unregisterFileReceiveListener() {
        if (!fileReceiveListenerRegistered) {
            return
        }
        PSecuritySDK.getMessageService()?.getFileOperater()?.removeFileReceiveV2Listener(fileReceiveListener)
        PSecuritySDK.getMessageService()?.getBtFileOperater()?.removeFileReceiveV2Listener(fileReceiveListener)
        fileReceiveListenerRegistered = false
    }

    private fun isImageOrVideo(file: File): Boolean {
        return isImage(file) || isVideo(file)
    }

    private fun isImage(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
    }

    private fun isVideo(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mov")
    }
}
