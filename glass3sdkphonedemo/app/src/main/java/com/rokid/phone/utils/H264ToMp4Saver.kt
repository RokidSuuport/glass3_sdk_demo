package com.rokid.phone.utils

import android.util.Log
import com.coremedia.iso.IsoFile
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.tracks.h264.H264TrackImpl
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel

class H264ToMp4Saver(private val outputPath: String) {

    private val h264File: File = File("$outputPath.h264").apply {
        parentFile?.mkdirs()  // 确保目录存在
    }

    fun appendFrame(data: ByteArray) {
        Log.e("aaaa","写入")
        if (data.isEmpty()) return
        FileOutputStream(h264File, true).use { fos ->
            fos.write(data)
        }
    }

    fun finish() {
        if (!h264File.exists() || h264File.length() == 0L) {
            println("H264 file is empty, skipping MP4 conversion")
            return
        }

        val h264Track: Track = H264TrackImpl(FileDataSourceImpl(h264File.absolutePath))
        val movie = Movie()
        movie.addTrack(h264Track)

        val mp4Builder = DefaultMp4Builder()
        val container = mp4Builder.build(movie)

        val mp4File = File(outputPath)
        mp4File.parentFile?.mkdirs()
        FileOutputStream(mp4File).channel.use { fc: FileChannel ->
            container.writeContainer(fc)
        }

        // 删除临时文件
        h264File.delete()
    }
}
