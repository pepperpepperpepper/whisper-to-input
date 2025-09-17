/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2024 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.util.Log

import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import com.example.whispertoinput.WhisperTranscriber
import com.github.liuyueyi.quick.transfer.ChineseUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val RECORDED_AUDIO_FILENAME_M4A = "recorded.m4a"
private const val RECORDED_AUDIO_FILENAME_OGG = "recorded.ogg"
private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val AUDIO_MEDIA_TYPE_OGG = "audio/ogg"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28
private const val WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes maximum recording time

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = ""
    private var useOggFormat: Boolean = false
    private var pendingAttachToEnd: String = ""
    private var isFirstTime: Boolean = true
    
    // Wake lock to prevent device sleep during recording
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockTimeoutJob: kotlinx.coroutines.Job? = null

    /**
     * Cleans up the recorded audio file if it exists
     */
    private fun cleanupAudioFile() {
        if (recordedAudioFilename.isNotEmpty()) {
            try {
                val file = File(recordedAudioFilename)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        Log.d("WhisperInputService", "Audio file deleted: $recordedAudioFilename")
                    } else {
                        Log.w("WhisperInputService", "Failed to delete audio file: $recordedAudioFilename")
                    }
                }
            } catch (e: Exception) {
                Log.e("WhisperInputService", "Error cleaning up audio file: $recordedAudioFilename", e)
            }
        }
    }

    /**
     * Cleans up all possible audio files from previous sessions
     */
    private fun cleanupAllAudioFiles() {
        val cacheDir = externalCacheDir
        if (cacheDir != null) {
            try {
                // Clean up both possible audio file formats
                val m4aFile = File("${cacheDir.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}")
                val oggFile = File("${cacheDir.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}")
                
                var filesDeleted = 0
                
                if (m4aFile.exists()) {
                    if (m4aFile.delete()) {
                        filesDeleted++
                        Log.d("WhisperInputService", "Cleaned up leftover M4A file: ${m4aFile.absolutePath}")
                    } else {
                        Log.w("WhisperInputService", "Failed to delete leftover M4A file: ${m4aFile.absolutePath}")
                    }
                }
                
                if (oggFile.exists()) {
                    if (oggFile.delete()) {
                        filesDeleted++
                        Log.d("WhisperInputService", "Cleaned up leftover OGG file: ${oggFile.absolutePath}")
                    } else {
                        Log.w("WhisperInputService", "Failed to delete leftover OGG file: ${oggFile.absolutePath}")
                    }
                }
                
                if (filesDeleted > 0) {
                    Log.i("WhisperInputService", "Cleaned up $filesDeleted leftover audio file(s) from previous sessions")
                }
            } catch (e: Exception) {
                Log.e("WhisperInputService", "Error cleaning up leftover audio files", e)
            }
        }
    }

    /**
     * Acquires a wake lock to prevent the device from sleeping during recording
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "WhisperInputService::RecordingWakeLock"
            )
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d("WhisperInputService", "Wake lock acquired to prevent device sleep during recording")
            
            // Set up timeout to automatically release wake lock after maximum recording time
            wakeLockTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
                delay(WAKE_LOCK_TIMEOUT_MS)
                Log.w("WhisperInputService", "Recording timeout reached, releasing wake lock")
                releaseWakeLock()
                // Also stop recording if it's still active
                if (whisperKeyboard.getCurrentStatus() == WhisperKeyboard.KeyboardStatus.Recording) {
                    Log.i("WhisperInputService", "Auto-stopping recording due to timeout")
                    onCancelRecording()
                }
            }
        } catch (e: Exception) {
            Log.e("WhisperInputService", "Failed to acquire wake lock", e)
        }
    }

    /**
     * Releases the wake lock to allow the device to sleep normally
     */
    private fun releaseWakeLock() {
        try {
            wakeLockTimeoutJob?.cancel()
            wakeLockTimeoutJob = null
            
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("WhisperInputService", "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e("WhisperInputService", "Failed to release wake lock", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Clean up any leftover audio files from previous sessions when service is created
        cleanupAllAudioFiles()
    }

    private fun transcriptionCallback(text: String?) {
        if (!text.isNullOrEmpty()) {
            currentInputConnection?.commitText(text, 1)
            // Check if auto-switch-back is enabled and switch if so
            CoroutineScope(Dispatchers.Main).launch {
                val autoSwitchBack = dataStore.data.map { preferences: Preferences ->
                    preferences[AUTO_SWITCH_BACK] ?: false
                }.first()
                if (autoSwitchBack) {
                    onSwitchIme()
                }
            }
        }
        whisperKeyboard.reset()
    }

    private fun transcriptionExceptionCallback(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        whisperKeyboard.reset()
    }

    private suspend fun updateAudioFormat() {
        val backend = dataStore.data.map { preferences: Preferences ->
            preferences[SPEECH_TO_TEXT_BACKEND] ?: getString(R.string.settings_option_openai_api)
        }.first()
        
        useOggFormat = backend == getString(R.string.settings_option_nvidia_nim)
        if (useOggFormat) {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_OGG}"
            audioMediaType = AUDIO_MEDIA_TYPE_OGG
        } else {
            recordedAudioFilename = "${externalCacheDir?.absolutePath}/${RECORDED_AUDIO_FILENAME_M4A}"
            audioMediaType = AUDIO_MEDIA_TYPE_M4A
        }
    }

    override fun onCreateInputView(): View {
        // Initialize members with regard to this context
        recorderManager = RecorderManager(this)

        // Preload conversion table
        // TODO: Fix TransType import
        // ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TAIWAN)
        // ChineseUtils.preLoad(true, TransType.TAIWAN_TO_SIMPLE)

        // Initialize audio format based on backend setting
        CoroutineScope(Dispatchers.Main).launch {
            updateAudioFormat()
        }

        // Should offer ime switch?
        val shouldOfferImeSwitch: Boolean =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val inputMethodManager =
                    getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                inputMethodManager.shouldOfferSwitchingToNextInputMethod(token)
            }

        // Sets up recorder manager
        recorderManager!!.setOnUpdateMicrophoneAmplitude { amplitude ->
            onUpdateMicrophoneAmplitude(amplitude)
        }
        
        recorderManager!!.setOnRecordingStopped { success, errorMessage ->
            onRecordingStopped(success, errorMessage)
        }

        // Returns the keyboard after setting it up and inflating its layout
        return whisperKeyboard.setup(layoutInflater,
            shouldOfferImeSwitch,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
        )
    }

    /**
     * Validates that the audio file exists and has a reasonable size
     * @return true if file is valid, false otherwise
     */
    private fun isAudioFileValid(filename: String): Boolean {
        val file = File(filename)
        return when {
            !file.exists() -> {
                Log.e("whisper-input", "Audio file does not exist: $filename")
                false
            }
            file.length() == 0L -> {
                Log.e("whisper-input", "Audio file is empty: $filename")
                false
            }
            file.length() < 1024 -> { // Less than 1KB is likely too small for valid audio
                Log.e("whisper-input", "Audio file is too small (${file.length()} bytes): $filename")
                false
            }
            else -> {
                Log.d("whisper-input", "Audio file is valid: $filename (${file.length()} bytes)")
                true
            }
        }
    }

    /**
     * Waits for the audio file to be ready with a timeout
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true if file is ready within timeout, false otherwise
     */
    private suspend fun waitForAudioFileReady(filename: String, timeoutMs: Long = 3000): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isAudioFileValid(filename)) {
                return true
            }
            delay(100) // Check every 100ms
        }
        
        Log.e("whisper-input", "Audio file not ready after ${timeoutMs}ms: $filename")
        return false
    }

    /**
     * Waits for the audio file to be completely written and stable
     * @param timeoutMs maximum time to wait in milliseconds
     * @return true if file is stable and ready, false otherwise
     */
    private suspend fun waitForAudioFileStable(filename: String, timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        var lastFileSize: Long = -1
        var stableCount = 0
        val requiredStableChecks = 3 // File size must remain stable for 3 consecutive checks
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val file = File(filename)
            
            if (!file.exists()) {
                Log.d("whisper-input", "Audio file not yet available: $filename")
                delay(100)
                continue
            }
            
            val currentSize = file.length()
            
            if (currentSize == lastFileSize) {
                stableCount++
                Log.d("whisper-input", "Audio file size stable ($stableCount/$requiredStableChecks): ${currentSize} bytes")
                
                if (stableCount >= requiredStableChecks) {
                    // File size has been stable for multiple checks, consider it ready
                    if (isAudioFileValid(filename)) {
                        Log.i("whisper-input", "Audio file is stable and valid: $filename (${currentSize} bytes)")
                        return true
                    } else {
                        Log.e("whisper-input", "Audio file is stable but invalid: $filename")
                        return false
                    }
                }
            } else {
                // File size changed, reset stability counter
                if (lastFileSize != -1L) {
                    Log.d("whisper-input", "Audio file size changed from ${lastFileSize} to ${currentSize} bytes")
                }
                lastFileSize = currentSize
                stableCount = 0
            }
            
            delay(200) // Check every 200ms
        }
        
        Log.e("whisper-input", "Audio file did not stabilize within ${timeoutMs}ms: $filename")
        return false
    }

    /**
     * Callback for when recording has stopped
     */
    private fun onRecordingStopped(success: Boolean, errorMessage: String?) {
        if (!success) {
            Log.e("whisper-input", "Recording stopped with error: $errorMessage")
            whisperKeyboard.reset()
            return
        }
        
        Log.d("whisper-input", "Recording stopped successfully, waiting for file to be stable")
        
        // Launch a coroutine to wait for the file to be stable and then start transcription
        CoroutineScope(Dispatchers.Main).launch {
            val fileStable = waitForAudioFileStable(recordedAudioFilename)
            if (fileStable) {
                Log.d("whisper-input", "Audio file stable, starting transcription")
                startTranscriptionWithValidatedFile()
            } else {
                Log.e("whisper-input", "Audio file not stable, transcription cancelled")
                whisperKeyboard.reset()
            }
        }
    }

    /**
     * Starts transcription with a validated audio file
     */
    private fun startTranscriptionWithValidatedFile() {
        whisperTranscriber.startAsync(this,
            recordedAudioFilename,
            audioMediaType,
            pendingAttachToEnd,
            { transcriptionCallback(it) },
            { transcriptionExceptionCallback(it) })
    }

    private fun onStartRecording() {
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

        // Acquire wake lock to prevent device sleep during recording
        acquireWakeLock()

        recorderManager!!.start(this, recordedAudioFilename, useOggFormat)
    }

    // when mic amplitude is updated, notify the keyboard
    // this callback is registered to the recorder manager
    private fun onUpdateMicrophoneAmplitude(amplitude: Int) {
        whisperKeyboard.updateMicrophoneAmplitude(amplitude)
    }

    private fun onCancelRecording() {
        recorderManager!!.stop()
        cleanupAudioFile()
        releaseWakeLock()
    }

    private fun onStartTranscription(attachToEnd: String) {
        // Store the attachToEnd parameter for use after recording stops
        pendingAttachToEnd = attachToEnd
        Log.d("whisper-input", "Starting transcription process, stopping recording")
        recorderManager!!.stop()
        releaseWakeLock()
    }

    private fun onCancelTranscription() {
        whisperTranscriber.stop()
    }

    private fun onDeleteText() {
        val inputConnection = currentInputConnection ?: return
        val selectedText = inputConnection.getSelectedText(0)

        // Deletes cursor pointed text, or all selected texts
        if (TextUtils.isEmpty(selectedText)) {
            inputConnection.deleteSurroundingText(1, 0)
        } else {
            inputConnection.commitText("", 1)
        }
    }

    private fun onSwitchIme() {
        // Before API Level 28, switchToPreviousInputMethod() was not available
        if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val token: IBinder? = window?.window?.attributes?.token
            inputMethodManager.switchToLastInputMethod(token)
        }

    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun onEnter() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }

    private fun onSpaceBar() {
        val inputConnection = currentInputConnection ?: return
        inputConnection.commitText(" ", 1)
    }

    private fun shouldShowRetry(): Boolean {
        val exists = File(recordedAudioFilename).exists()
        return exists
    }

    // Opens up app MainActivity
    private fun launchMainActivity() {
        val dialogIntent = Intent(this, MainActivity::class.java)
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(dialogIntent)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()

        // If this is the first time calling onWindowShown, it means this IME is just being switched to.
        // Automatically starts recording after switching to Whisper Input. (if settings enabled)
        // Dispatch a coroutine to do this task.
        CoroutineScope(Dispatchers.Main).launch {
            // Update audio format based on current backend setting
            updateAudioFormat()
            if (!isFirstTime) return@launch
            isFirstTime = false
            val isAutoStartRecording = dataStore.data.map { preferences: Preferences ->
                preferences[AUTO_RECORDING_START] ?: true
            }.first()
            if (isAutoStartRecording) {
                whisperKeyboard.tryStartRecording()
            }
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
        cleanupAudioFile()
        releaseWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
        cleanupAudioFile()
        releaseWakeLock()
    }
}
