package com.rifters.riftedreader.ui.reader.conveyor

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rifters.riftedreader.databinding.ActivityConveyorDebugBinding
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Standalone debug activity for testing the isolated conveyor belt system.
 * 
 * **This activity can be launched independently from the main reader.**
 * 
 * ## Features
 * 
 * - Manual window transition simulation
 * - Real-time phase and buffer state display
 * - Event log inspection
 * - No dependencies on book content or legacy reader code
 * 
 * ## How to Launch
 * 
 * 1. Via ADB:
 *    ```
 *    adb shell am start -n com.rifters.riftedreader/.ui.reader.conveyor.ConveyorDebugActivity
 *    ```
 * 
 * 2. Programmatically:
 *    ```kotlin
 *    startActivity(Intent(this, ConveyorDebugActivity::class.java))
 *    ```
 * 
 * ## Use Cases
 * 
 * - Validate that the conveyor belt logic works correctly in isolation
 * - Debug phase transition issues without interference from legacy code
 * - Compare behavior with the integrated WindowBufferManager
 * 
 * @see ConveyorBeltSystemViewModel for the underlying logic
 */
class ConveyorDebugActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ConveyorDebugActivity"
    }
    
    private lateinit var binding: ActivityConveyorDebugBinding
    
    private val viewModel: ConveyorBeltSystemViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityConveyorDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        AppLogger.d(TAG, "[CONVEYOR_DEBUG] Activity created")
        
        setupUI()
        observeViewModel()
        
        // Initialize with default values
        initializeSystem()
    }
    
    private fun setupUI() {
        // Initialize button
        binding.initializeButton.setOnClickListener {
            initializeSystem()
        }
        
        // Navigation buttons
        binding.prevButton.setOnClickListener {
            AppLogger.d(TAG, "[CONVEYOR_DEBUG] Prev button clicked")
            viewModel.simulatePreviousWindow()
        }
        
        binding.nextButton.setOnClickListener {
            AppLogger.d(TAG, "[CONVEYOR_DEBUG] Next button clicked")
            viewModel.simulateNextWindow()
        }
        
        // Jump to center button
        binding.jumpToCenterButton.setOnClickListener {
            val center = viewModel.getCenterWindow()
            if (center != null) {
                AppLogger.d(TAG, "[CONVEYOR_DEBUG] Jumping to center window $center")
                viewModel.onWindowEntered(center)
            }
        }
        
        // Clear log button
        binding.clearLogButton.setOnClickListener {
            viewModel.clearEventLog()
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe phase
                launch {
                    viewModel.phase.collectLatest { phase ->
                        binding.phaseText.text = phase.name
                        // Color code the phase
                        binding.phaseText.setTextColor(
                            if (phase == ConveyorPhase.STEADY) 
                                getColor(android.R.color.holo_green_dark)
                            else 
                                getColor(android.R.color.holo_orange_dark)
                        )
                    }
                }
                
                // Observe active window
                launch {
                    viewModel.activeWindow.collectLatest { window ->
                        binding.activeWindowText.text = window.toString()
                    }
                }
                
                // Observe buffer contents
                launch {
                    viewModel.buffer.collectLatest { buffer ->
                        binding.bufferText.text = buffer.toString()
                    }
                }
                
                // Observe center window (derived from buffer)
                launch {
                    viewModel.buffer.collectLatest { _ ->
                        val center = viewModel.getCenterWindow()
                        binding.centerWindowText.text = center?.toString() ?: "N/A"
                    }
                }
                
                // Observe event log
                launch {
                    viewModel.eventLog.collectLatest { log ->
                        // Format log entries for display
                        val logText = log.takeLast(50).joinToString("\n") { entry ->
                            // Extract just the event and message parts
                            entry.substringAfter("] ").take(100)
                        }
                        binding.logText.text = logText
                        
                        // Auto-scroll to bottom
                        binding.logScrollView.post {
                            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }
    
    private fun initializeSystem() {
        val totalWindowsText = binding.totalWindowsInput.text?.toString() ?: "10"
        val totalWindows = totalWindowsText.toIntOrNull() ?: 10
        
        AppLogger.d(TAG, "[CONVEYOR_DEBUG] Initializing with $totalWindows windows")
        
        viewModel.initialize(startWindow = 0, totalWindowCount = totalWindows)
    }
}
