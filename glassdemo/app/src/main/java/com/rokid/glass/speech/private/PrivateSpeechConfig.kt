package com.rokid.glass.speech.privateservice

import com.rokid.glesse.BuildConfig

data class PrivateSpeechConfig(
    val domain: String,
    val ak: String,
    val sk: String,
    val uid: String,
    val deviceId: String,
    val asrPath: String,
    val ttsPath: String,
    val trustAllCerts: Boolean,
) {
    fun missingRequiredFields(): List<String> = listOf(
        "domain" to domain,
        "ak" to ak,
        "sk" to sk,
        "uid" to uid,
        "deviceId" to deviceId,
        "asrPath" to asrPath,
        "ttsPath" to ttsPath,
    ).filter { (_, value) -> value.isBlank() }
        .map { (name, _) -> name }

    companion object {
        fun fromBuildConfig(): PrivateSpeechConfig = PrivateSpeechConfig(
            domain = BuildConfig.PRIVATE_SPEECH_DOMAIN,
            ak = BuildConfig.PRIVATE_SPEECH_AK,
            sk = BuildConfig.PRIVATE_SPEECH_SK,
            uid = BuildConfig.PRIVATE_SPEECH_UID,
            deviceId = BuildConfig.PRIVATE_SPEECH_DEVICE_ID,
            asrPath = BuildConfig.PRIVATE_SPEECH_ASR_PATH,
            ttsPath = BuildConfig.PRIVATE_SPEECH_TTS_PATH,
            trustAllCerts = BuildConfig.PRIVATE_SPEECH_TRUST_ALL_CERTS,
        )
    }
}
