package com.rokid.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rokid.phone.adapter.MediaAdapter
import java.io.File

class GalleryActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MediaAdapter
    private val mediaList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.recyclerView)
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
                val intent = Intent(this, ImagePreviewActivity::class.java)
                intent.putExtra("imagePath", file.absolutePath)
                startActivity(intent)
            }
        }
        recyclerView.adapter = adapter
        loadMedia()
    }

    private fun loadMedia() {
        val appMediaDir = getExternalFilesDir(null)
        if (appMediaDir != null && appMediaDir.exists()) {
            mediaList.clear()
            scanFiles(appMediaDir)
            adapter.notifyDataSetChanged()
        } else {
            Toast.makeText(this, "目录不存在: ${appMediaDir?.path}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanFiles(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanFiles(file)
            } else {
                if (isImageOrVideo(file)) {
                    mediaList.add(file)
                }
            }
        }
    }

    private fun isImageOrVideo(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mov")
    }
}