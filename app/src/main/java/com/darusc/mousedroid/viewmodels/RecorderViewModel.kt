package com.darusc.mousedroid.viewmodels

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

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
    private var playbackScope: CoroutineScope? = null

    val isRecording = MutableLiveData(false)
    val isPlaying = MutableLiveData(false)
    val recordedEventCount = MutableLiveData(0)
    val playbackInterval = MutableLiveData(100L)
    val isLoopEnabled = MutableLiveData(false)
    val savedRecordings = MutableLiveData<List<String>>(emptyList())

    fun recordEvent(event: InputEvent) {
        if (_isRecording) {
            val currentTime = System.currentTimeMillis()
            val delay = maxOf(0L, currentTime - lastEventTime)
            recordedEvents.add(RecordedEvent(event, delay))
            lastEventTime = currentTime
            recordedEventCount.value = recordedEvents.size
            
            // Also send to device while recording
            connectionManager.send(event)
        }
    }

    fun toggleRecording() {
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
        playbackScope = CoroutineScope(Dispatchers.Main)

        playbackScope?.launch {
            try {
                do {
                    for (recordedEvent in recordedEvents) {
                        if (!_isPlaying) break

                        val interval = playbackInterval.value ?: 100L
                        if (interval > 0) {
                            delay(interval)
                        } else if (recordedEvent.delayFromPrevious > 0) {
                            delay(recordedEvent.delayFromPrevious)
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
        playbackScope?.cancel()
        playbackScope = null
    }

    fun clearRecording() {
        stopRecording()
        recordedEvents.clear()
        lastEventTime = 0L
        recordedEventCount.value = 0
    }

    fun saveRecording(context: Context, filename: String = generateFilename()): Boolean {
        return try {
            val file = File(context.filesDir, "$filename.recording")
            ObjectOutputStream(FileOutputStream(file)).use { oos ->
                oos.writeObject(recordedEvents)
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
        playbackInterval.value = (playbackInterval.value ?: 100L) + 50L
    }

    fun decrementPlaybackInterval() {
        playbackInterval.value = maxOf(0L, (playbackInterval.value ?: 100L) - 50L)
    }

    private fun generateFilename(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "recording_${sdf.format(Date())}"
    }
}
