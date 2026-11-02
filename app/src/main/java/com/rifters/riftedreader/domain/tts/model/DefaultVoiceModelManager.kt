package com.rifters.riftedreader.domain.tts.model

import android.content.Context
import com.rifters.riftedreader.domain.tts.TTSEngineType

/**
 * VoiceModelManager stub implementation (Stage 4 neural voices scaffolding).
 */
class DefaultVoiceModelManager(
    private val context: Context
) : VoiceModelManager {

    override suspend fun listAvailableVoices(): List<VoiceModel> {
        return listOf(
            VoiceModel(
                id = "system_default",
                name = "System Voice",
                languageCode = "en",
                engineType = TTSEngineType.SYSTEM,
                isDownloaded = true
            ),
            VoiceModel(
                id = "sherpa_vits_piper_en_libritts",
                name = "Piper LibriTTS (Sherpa ONNX)",
                languageCode = "en-US",
                engineType = TTSEngineType.SHERPA_ONNX,
                sizeBytes = 65_000_000L,
                isDownloaded = false
            )
        )
    }

    override suspend fun listInstalledVoices(): List<VoiceModel> {
        return listAvailableVoices().filter { it.isDownloaded }
    }

    override suspend fun getSelectedVoice(): VoiceModel? {
        return listInstalledVoices().firstOrNull()
    }

    override suspend fun selectVoice(voiceId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun downloadVoice(
        voice: VoiceModel,
        onProgress: (Float) -> Unit
    ): Result<VoiceModel> {
        return Result.failure(NotImplementedError("Neural voice model downloads pending Stage 4 implementation"))
    }

    override suspend fun deleteVoice(voiceId: String): Result<Unit> {
        return Result.success(Unit)
    }
}
