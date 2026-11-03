package com.rifters.riftedreader.domain.tts.model

import com.rifters.riftedreader.domain.tts.TTSEngineType

data class VoiceModel(
    val id: String,
    val name: String,
    val languageCode: String,
    val engineType: TTSEngineType,
    val localFilePath: String? = null,
    val sizeBytes: Long = 0L,
    val isDownloaded: Boolean = false,
    val sampleAudioUrl: String? = null
)

interface VoiceModelManager {
    suspend fun listAvailableVoices(): List<VoiceModel>
    suspend fun listInstalledVoices(): List<VoiceModel>
    suspend fun getSelectedVoice(): VoiceModel?
    suspend fun selectVoice(voiceId: String): Result<Unit>
    suspend fun downloadVoice(voice: VoiceModel, onProgress: (Float) -> Unit): Result<VoiceModel>
    suspend fun deleteVoice(voiceId: String): Result<Unit>
}
