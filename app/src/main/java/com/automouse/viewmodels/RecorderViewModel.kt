package com.automouse.viewmodels

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.automouse.mkinput.InputEvent
import com.automouse.networking.ConnectionManager
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import android.view.View

class RecorderViewModel : BaseViewModel<RecorderViewModel.State, RecorderViewModel.Event>(State()) {

    class State : BaseViewModel.State()
    sealed class Event : BaseViewModel.Event()

    private val connectionManager = ConnectionManager.getInstance()

    private data class RecordedEvent(
        val event: InputEvent,
        val delayFromPrevious: Long
    ) : java.io.Serializable

    private var _isRecording = false
    private var _isPlaying = false
    private var recordedEvents = mutableListOf<RecordedEvent>()
    private var lastEventTime = 0L
    private var playbackJob: Job? = null

    val isRecording = MutableLiveData(false)
    val isPlaying = MutableLiveData(false)
    val recordedEventCount = MutableLiveData(0)
    val playbackMultiplier = MutableLiveData(1.0f)
    val isLoopEnabled = MutableLiveData(false)
    val savedRecordings = MutableLiveData<List<String>>(emptyList())

    fun recordEvent(event: InputEvent) {
        if (_isRecording) {
            val currentTime = System.currentTimeMillis()
            val delay = maxOf(0L, currentTime - lastEventTime)
            recordedEvents.add(RecordedEvent(event, delay))
            lastEventTime = currentTime
            recordedEventCount.value = recordedEvents.size
        }
        
        // Always send to device for real-time movement
        connectionManager.send(event)
    }

    fun toggleRecording() {
        if (_isPlaying) stopPlayback()
        if (_isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        _isRecording = true
        this.isRecording.value = true
        recordedEvents.clear()
        lastEventTime = System.currentTimeMillis()
        recordedEventCount.value = 0
    }

    private fun stopRecording() {
        _isRecording = false
        this.isRecording.value = false
    }

    fun togglePlayback() {
        if (_isRecording) stopRecording()
        if (_isPlaying) {
            stopPlayback()
        } else {
            playRecording()
        }
    }

    private fun playRecording() {
        if (recordedEvents.isEmpty() || _isPlaying) return

        _isPlaying = true
        this.isPlaying.value = true
        
        val eventsToPlay = recordedEvents.toList()

        playbackJob = viewModelScope.launch {
            try {
                do {
                    for (recordedEvent in eventsToPlay) {
                        if (!_isPlaying) break

                        val multiplier = playbackMultiplier.value ?: 1.0f
                        val delayMs = (recordedEvent.delayFromPrevious / multiplier).toLong()
                        if (delayMs > 0) {
                            delay(delayMs)
                        }

                        if (_isPlaying) {
                            connectionManager.send(recordedEvent.event)
                        }
                    }
                } while (isLoopEnabled.value == true && _isPlaying)
            } finally {
                _isPlaying = false
                this@RecorderViewModel.isPlaying.value = false
            }
        }
    }

    fun stopPlayback() {
        _isPlaying = false
        this.isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun clearRecording() {
        stopRecording()
        recordedEvents.clear()
        lastEventTime = 0L
        recordedEventCount.value = 0
    }

    fun saveRecording(context: Context, filename: String = generateFilename()): Boolean {
        if (_isRecording) stopRecording()
        val eventsToSave = ArrayList(recordedEvents)
        return try {
            val file = File(context.filesDir, "$filename.recording")
            ObjectOutputStream(FileOutputStream(file)).use { oos ->
                oos.writeObject(eventsToSave)
            }
            refreshSavedRecordings(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadRecording(context: Context, filename: String): Boolean {
        return try {
            val file = File(context.filesDir, filename)
            if (!file.exists()) return false

            ObjectInputStream(FileInputStream(file)).use { ois ->
                @Suppress("UNCHECKED_CAST")
                recordedEvents = ois.readObject() as MutableList<RecordedEvent>
            }
            recordedEventCount.value = recordedEvents.size
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteRecording(context: Context, filename: String): Boolean {
        return try {
            File(context.filesDir, filename).delete().also {
                if (it) refreshSavedRecordings(context)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun refreshSavedRecordings(context: Context) {
        try {
            val recordings = context.filesDir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".recording")
            }?.map { it.name } ?: emptyList()
            savedRecordings.value = recordings
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun incrementPlaybackInterval() {
        val current = playbackMultiplier.value ?: 1.0f
        playbackMultiplier.value = minOf(10.0f, current + 0.25f)
    }

    fun decrementPlaybackInterval() {
        val current = playbackMultiplier.value ?: 1.0f
        playbackMultiplier.value = maxOf(0.25f, current - 0.25f)
    }

    private fun generateFilename(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "recording_${sdf.format(Date())}"
    }

    fun onMouseButtonClick(view: View) {
        val button = when (view.id) {
            com.automouse.R.id.btnLeftClick -> InputEvent.MouseButton.LEFT
            com.automouse.R.id.btnRightClick -> InputEvent.MouseButton.RIGHT
            com.automouse.R.id.btnMiddleClick -> InputEvent.MouseButton.MIDDLE
            else -> return
        }
        recordEvent(InputEvent.MouseClick(button))
    }
}
