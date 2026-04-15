package com.rokid.glass.media

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录制参数
 * @param mediaType 媒体类型
 * @param recFile 视频父路径
 * @param audioData 音频参数
 * @param videoData 视频参数
 */
data class RecordData(
    val mediaType: MediaType,
    var recFile: File,
    val audioData: AudioData,
    val videoData: VideoData,
) {

    companion object {

        /**
         * 录音
         */
        fun newAudioRecordData(
            parentDir: File,
            audioData: AudioData
        ): RecordData =
            if (audioData.isAvailable()) {
                RecordData(
                    MediaType.VIDEO,
                    generateRecFile(MediaType.VIDEO, parentDir),
                    audioData,
                    VideoData()
                )
            } else {
                throw IllegalArgumentException("Invalid audio record data.")
            }

        /**
         * 视频.
         */
        fun newVideoRecordData(
            file: File,
            audioData: AudioData,
            videoData: VideoData
        ): RecordData {
            if (!file.exists()) {
                file.mkdirs()
            }
            return if (audioData.isAvailable() && videoData.isAvailable()) {
                RecordData(
                    MediaType.VIDEO,
                    if(file.isDirectory) generateRecFile(MediaType.VIDEO, file) else file ,
                    audioData,
                    videoData
                )
            } else {
                throw IllegalArgumentException("Invalid video record data.")
            }
        }


        /**
         * 文件生成
         * {MediaType}-{DATE}-{UUID}.{ext}
         */
        private fun generateRecFile(mediaType: MediaType, parentDir: File): File {
            if (!parentDir.isDirectory) {
                throw IOException("Parent dir is not directory.")
            }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
            val fileName = String.format(
                Locale.US, "%s-%s.%s",
                mediaType.toString(), dateFormat.format(Date()), mediaType.ext
            )
            val recFile = File(parentDir, fileName)
            if (recFile.exists()) {
                recFile.delete()
            }
            if (!recFile.createNewFile()) {
                throw IOException("Failed to create new file. ${recFile.absolutePath}")
            }
            logI("filePath: ${recFile.canonicalPath}")
            return recFile
        }

    }

    fun newFile(): File {
        val file = generateRecFile(mediaType, recFile.parentFile)
        recFile = file
        return file
    }


}