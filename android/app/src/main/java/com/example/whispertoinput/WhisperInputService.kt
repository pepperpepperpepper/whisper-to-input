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
import android.content.Intent
import android.os.IBinder
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

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard: WhisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber: WhisperTranscriber = WhisperTranscriber()
    private var recorderManager: RecorderManager? = null
    private var recordedAudioFilename: String = ""
    private var audioMediaType: String = ""
    private var useOggFormat: Boolean = false
    private var pendingAttachToEnd: String = ""
    private var isFirstTime: Boolean = true

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
     * Callback for when recording has stopped
     */
    private fun onRecordingStopped(success: Boolean, errorMessage: String?) {
        if (!success) {
            Log.e("whisper-input", "Recording stopped with error: $errorMessage")
            whisperKeyboard.reset()
            return
        }
        
        Log.d("whisper-input", "Recording stopped successfully, waiting for file to be ready")
        
        // Launch a coroutine to wait for the file to be ready and then start transcription
        CoroutineScope(Dispatchers.Main).launch {
            val fileReady = waitForAudioFileReady(recordedAudioFilename)
            if (fileReady) {
                Log.d("whisper-input", "Audio file ready, starting transcription")
                startTranscriptionWithValidatedFile()
            } else {
                Log.e("whisper-input", "Audio file not ready, transcription cancelled")
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
        // Clean up any existing audio files to ensure we start with a clean slate
        cleanupAllAudioFiles()
        
        // Upon starting recording, check whether audio permission is granted.
        if (!recorderManager!!.allPermissionsGranted(this)) {
            // If not, launch app MainActivity (for permission setup).
            launchMainActivity()
            whisperKeyboard.reset()
            return
        }

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
    }

    private fun onStartTranscription(attachToEnd: String) {
        // Store the attachToEnd parameter for use after recording stops
        pendingAttachToEnd = attachToEnd
        Log.d("whisper-input", "Starting transcription process, stopping recording")
        recorderManager!!.stop()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperTranscriber.stop()
        whisperKeyboard.reset()
        recorderManager!!.stop()
        cleanupAudioFile()
    }
}
