package com.isvisoft.flutter_screen_recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies all tracks from one or more input media files into a single output.
 * Used to merge MediaRecorder video with internal-audio AAC.
 */
class RecordingMuxer(
    private val format: Int,
    private val outFile: String,
    private vararg val inputFiles: String,
) {
    fun mux() {
        val muxer = MediaMuxer(outFile, format)
        val extractors = ArrayList<MediaExtractor>()
        val map = LinkedHashMap<Pair<MediaExtractor, Int>, Int>()

        try {
            for (file in inputFiles) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file)
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to open extractor for $file: ${e.message}")
                    extractor.release()
                    continue
                }
                extractors.add(extractor)
                for (i in 0 until extractor.trackCount) {
                    val muxId = muxer.addTrack(extractor.getTrackFormat(i))
                    map[Pair(extractor, i)] = muxId
                }
            }

            if (map.isEmpty()) {
                throw IOException("No tracks found to mux")
            }

            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            for ((pair, muxId) in map) {
                val extractor = pair.first
                val trackIndex = pair.second
                extractor.selectTrack(trackIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val offset = buffer.arrayOffset()
                    info.size = extractor.readSampleData(buffer, offset)
                    if (info.size < 0) {
                        break
                    }
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    info.offset = 0
                    muxer.writeSampleData(muxId, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(trackIndex)
            }
        } finally {
            for (extractor in extractors) {
                try {
                    extractor.release()
                } catch (_: Exception) {
                }
            }
            try {
                muxer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Muxer stop failed: ${e.message}")
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "RecordingMuxer"
        private const val BUFFER_SIZE = 1024 * 1024
    }
}
