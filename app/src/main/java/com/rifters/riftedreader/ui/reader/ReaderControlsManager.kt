package com.rifters.riftedreader.ui.reader

import android.view.View
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Handles showing and hiding of reader controls with an auto-hide timeout, mirroring
 * LibreraReader's overlay behaviour (see rifters/LibreraReader, ui2/reader/ReaderActivity.java, lines 210-290).
 */
class ReaderControlsManager(
    private val controlsContainer: View,
    private val scope: CoroutineScope,
    private val autoHideMillis: Long = DEFAULT_AUTO_HIDE_MS
) {

    private var hideJob: Job? = null
    private var autoHideEnabled: Boolean = true

    fun showControls() {
        hideJob?.cancel()
        if (!controlsContainer.isVisible) {
            // Post the visibility change to avoid requestLayout() during layout
            controlsContainer.post {
                controlsContainer.alpha = 0f
                controlsContainer.isVisible = true
                controlsContainer.animate().alpha(1f).setDuration(ANIMATION_DURATION_MS).start()
            }
        }
        scheduleAutoHide()
    }

    fun hideControls() {
        hideJob?.cancel()
        if (controlsContainer.isVisible) {
            controlsContainer.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction {
                    controlsContainer.isVisible = false
                    controlsContainer.alpha = 1f
                }
                .start()
        }
    }

    fun toggleControls() {
        if (controlsContainer.isVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    fun onUserInteraction() {
        if (controlsContainer.isVisible) {
            scheduleAutoHide()
        }
    }

    fun setAutoHideEnabled(enabled: Boolean) {
        autoHideEnabled = enabled
        if (!autoHideEnabled) {
            hideJob?.cancel()
        } else if (controlsContainer.isVisible) {
            scheduleAutoHide()
        }
    }

    private fun scheduleAutoHide() {
        hideJob?.cancel()
        if (!autoHideEnabled) {
            return
        }
        hideJob = scope.launch {
            delay(autoHideMillis)
            hideControls()
        }
    }

    companion object {
        private const val DEFAULT_AUTO_HIDE_MS = 3_500L
        private const val ANIMATION_DURATION_MS = 180L
    }
}
