package com.automouse

import android.content.Context
import com.automouse.mkinput.InputEvent
import kotlinx.coroutines.*
import java.io.*

class TouchpadRecorder {

    companion object {
        @Volatile
        private var instance: TouchpadRecorder? = null

        fun getInstance(): TouchpadRecorder {
            synchronized(this) {
                return instance ?: TouchpadRecorder().also { instance = it }
            }
        }
    }

    private data class RecordedEvent(
        val event: InputEvent,
        val delayFromPrevious: Long
    ) : java.io.Serializable

    private var isRecording = false
    private var isPlaying = false
    private var recordedEvents = mutableListOf<RecordedEvent>()
    private var lastEventTime = 0L
    private var playbackScope: CoroutineScope? = null

    interface RecorderListener {
        fun onRecordingStateChanged(isRecording: Boolean)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onEventRecorded(count: Int)
    }

    private var listener: RecorderListener? = null

    fun setListener(listener: RecorderListener?) {
        this.listener = listener
    }

    fun startRecording() {
        if (!isRecording) {
            isRecording = true
            recordedEvents.clear()
            lastEventTime = System.currentTimeMillis()
            listener?.onRecordingStateChanged(true)
        }
    }

    fun stopRecording() {
        if (isRecording) {
            isRecording = false
            listener?.onRecordingStateChanged(false)
        }
    }

    fun recordEvent(event: InputEvent) {
        if (isRecording) {
            val currentTime = System.currentTimeMillis()
            val delay = maxOf(0L, currentTime - lastEventTime)
            recordedEvents.add(RecordedEvent(event, delay))
            lastEventTime = currentTime
            listener?.onEventRecorded(recordedEvents.size)
        }
    }

    fun playRecording(
        sendEvent: (InputEvent) -> Unit,
        interval: Long = 0L,
        loop: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        if (recordedEvents.isEmpty() || isPlaying) return
        
        isPlaying = true
        listener?.onPlaybackStateChanged(true)
        playbackScope = CoroutineScope(Dispatchers.Main)
        
        playbackScope?.launch {
            try {
                do {
                    for (recordedEvent in recordedEvents) {
                        if (!isPlaying) break
                        
                        if (interval > 0) {
                            delay(interval)
                        } else if (recordedEvent.delayFromPrevious > 0) {
                            delay(recordedEvent.delayFromPrevious)
                        }
                        
                        if (isPlaying) {
                            sendEvent(recordedEvent.event)
                        }
                    }
                } while (loop && isPlaying)
            } finally {
                isPlaying = false
                listener?.onPlaybackStateChanged(false)
                onComplete()
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackScope?.cancel()
        playbackScope = null
        listener?.onPlaybackStateChanged(false)
    }

    fun saveRecording(context: Context, filename: String): Boolean {
        return try {
            val file = File(context.filesDir, filename)
            ObjectOutputStream(FileOutputStream(file)).use { oos ->
                oos.writeObject(recordedEvents)
            }
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getRecordedEventCount(): Int = recordedEvents.size
    
    fun isRecordingActive(): Boolean = isRecording
    
    fun isPlayingActive(): Boolean = isPlaying

    fun clearRecording() {
        recordedEvents.clear()
        lastEventTime = 0L
    }

    fun getSavedRecordings(context: Context): List<String> {
        return try {
            context.filesDir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".recording")
            }?.map { it.name } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteRecording(context: Context, filename: String): Boolean {
        return try {
            File(context.filesDir, filename).delete()
        } catch (e: Exception) {
            false
        }
    }
}
