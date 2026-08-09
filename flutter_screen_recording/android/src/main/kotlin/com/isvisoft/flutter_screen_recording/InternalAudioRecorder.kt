package com.isvisoft.flutter_screen_recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.annotation.RequiresApi
import android.os.Build
import android.util.Log
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Captures app/system media playback via [AudioPlaybackCaptureConfiguration]
 * and encodes AAC into an mp4 container (audio-only), matching AOSP screen-record.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class InternalAudioRecorder(
    outFile: String,
    mediaProjection: MediaProjection,
) {
    private val muxer =
        MediaMuxer(outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val mediaProjection = mediaProjection
    private val sampleRate = 44100
    private val bitRate = 128_000
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val channelMask = AudioFormat.CHANNEL_IN_MONO
    private val timeoutUs = 500L

    private val audioRecord: AudioRecord
    private val codec: MediaCodec
    private val thread: Thread

    private var trackId = -1
    private var presentationTimeUs = 0L
    private var totalBytes = 0L
    private var started = false

    init {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferSize = max(minBuffer * 2, 1 shl 17)

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IOException("Internal AudioRecord failed to initialize")
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val mediaFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
        }
        codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        thread = Thread({
            val buffer = ByteArray(bufferSize)
            while (true) {
                val readBytes = audioRecord.read(buffer, 0, buffer.size)
                if (readBytes < 0) {
                    Log.e(TAG, "AudioRecord read error: $readBytes")
                    break
                }
                if (readBytes == 0) {
                    continue
                }
                encode(buffer, readBytes)
            }
            endStream()
        }, "playora-internal-audio")
    }

    @Synchronized
    fun start() {
        if (started) {
            throw IllegalStateException("Internal audio recording already started")
        }
        started = true
        audioRecord.startRecording()
        codec.start()
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Internal audio failed to start recording")
        }
        thread.start()
        Log.d(TAG, "Internal audio capture started")
    }

    fun end() {
        try {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop failed: ${e.message}")
        }
        try {
            audioRecord.release()
        } catch (_: Exception) {
        }

        try {
            thread.join(5_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Codec stop failed: ${e.message}")
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }

        try {
            if (trackId >= 0) {
                muxer.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Muxer stop failed: ${e.message}")
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
        Log.d(TAG, "Internal audio capture ended")
    }

    private fun encode(buffer: ByteArray, readBytes: Int) {
        var remaining = readBytes
        var offset = 0
        while (remaining > 0) {
            val inputIndex = codec.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) {
                writeOutput()
                return
            }
            val input = codec.getInputBuffer(inputIndex) ?: return
            input.clear()
            val toWrite = min(remaining, input.capacity())
            input.put(buffer, offset, toWrite)
            remaining -= toWrite
            offset += toWrite
            totalBytes += toWrite.toLong()
            // mono PCM16 → bytes/2 samples
            presentationTimeUs = 1_000_000L * (totalBytes / 2) / sampleRate
            codec.queueInputBuffer(inputIndex, 0, toWrite, presentationTimeUs, 0)
            writeOutput()
        }
    }

    private fun endStream() {
        val inputIndex = codec.dequeueInputBuffer(timeoutUs)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        writeOutput()
    }

    private fun writeOutput() {
        while (true) {
            val info = MediaCodec.BufferInfo()
            val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackId = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex < 0 -> return
                else -> {
                    if (trackId < 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        return
                    }
                    val out = codec.getOutputBuffer(outputIndex)
                    if (out != null &&
                        info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        muxer.writeSampleData(trackId, out, info)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "InternalAudioRecorder"
    }
}
