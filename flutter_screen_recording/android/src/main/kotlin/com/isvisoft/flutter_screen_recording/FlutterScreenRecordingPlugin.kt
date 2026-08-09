package com.isvisoft.flutter_screen_recording

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.IOException

class FlutterScreenRecordingPlugin :
    MethodCallHandler,
    PluginRegistry.ActivityResultListener,
    FlutterPlugin,
    ActivityAware {

    private var mScreenDensity: Int = 0
    var mMediaRecorder: MediaRecorder? = null
    val mProjectionManager: MediaProjectionManager by lazy {
        ContextCompat.getSystemService(
            pluginBinding!!.applicationContext,
            MediaProjectionManager::class.java
        ) ?: throw Exception("MediaProjectionManager not found")
    }
    var mMediaProjection: MediaProjection? = null
    var mMediaProjectionCallback: MediaProjectionCallback? = null
    var mVirtualDisplay: VirtualDisplay? = null
    private var mDisplayWidth: Int = 1280
    private var mDisplayHeight: Int = 800
    private var videoName: String? = ""
    private var mFileName: String? = ""
    private var mTempVideoPath: String? = null
    private var mTempAudioPath: String? = null
    private var mTitle = "Your screen is being recorded"
    private var mMessage = "Your screen is being recorded"
    private var recordAudio: Boolean? = false
    private var useInternalAudio: Boolean = false
    private var internalAudioRecorder: InternalAudioRecorder? = null
    private val SCREEN_RECORD_REQUEST_CODE = 333

    private var pendingResult: Result? = null

    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var serviceConnection: ServiceConnection? = null

    private fun completePendingResult(value: Boolean) {
        pendingResult?.success(value)
        pendingResult = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val context = pluginBinding!!.applicationContext

        if (requestCode == SCREEN_RECORD_REQUEST_CODE) {
            if (pendingResult == null) {
                Log.w(TAG, "Ignoring activity result with no pending callback")
                if (resultCode != Activity.RESULT_OK) {
                    ForegroundService.stopService(context)
                }
                return true
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                ForegroundService.startService(context, mTitle, mMessage)
                val intentConnection = Intent(context, ForegroundService::class.java)

                serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        try {
                            mMediaProjectionCallback = MediaProjectionCallback()
                            mMediaProjection =
                                mProjectionManager.getMediaProjection(resultCode, data)
                            mMediaProjection?.registerCallback(mMediaProjectionCallback!!, null)

                            startRecordScreen()
                            mVirtualDisplay = createVirtualDisplay()
                            startInternalAudioIfNeeded()
                            completePendingResult(true)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to start recording: ${e.message}", e)
                            cleanupAfterFailure()
                            completePendingResult(false)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }

                val isBound = context.bindService(
                    intentConnection,
                    serviceConnection!!,
                    android.content.Context.BIND_AUTO_CREATE,
                )
                if (!isBound) {
                    ForegroundService.stopService(context)
                    completePendingResult(false)
                }
            } else {
                ForegroundService.stopService(context)
                completePendingResult(false)
            }
            return true
        }
        return false
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val appContext = pluginBinding!!.applicationContext

        when (call.method) {
            "startRecordScreen" -> {
                if (pendingResult != null) {
                    result.error(
                        "already_pending",
                        "A screen recording request is already pending.",
                        null
                    )
                    return
                }

                try {
                    pendingResult = result
                    val title = call.argument<String?>("title")
                    val message = call.argument<String?>("message")

                    if (!title.isNullOrEmpty()) {
                        mTitle = title
                    }
                    if (!message.isNullOrEmpty()) {
                        mMessage = message
                    }

                    val metrics = DisplayMetrics()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val display = activityBinding!!.activity.display
                        display?.getRealMetrics(metrics)
                    } else {
                        @SuppressLint("NewApi")
                        val defaultDisplay = appContext.display
                        defaultDisplay?.getMetrics(metrics)
                    }
                    mScreenDensity = metrics.densityDpi
                    calculateResolution(metrics)
                    videoName = call.argument<String?>("name")
                    recordAudio = call.argument<Boolean?>("audio") == true
                    useInternalAudio =
                        recordAudio == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                    val permissionIntent = mProjectionManager.createScreenCaptureIntent()
                    ActivityCompat.startActivityForResult(
                        activityBinding!!.activity,
                        permissionIntent,
                        SCREEN_RECORD_REQUEST_CODE,
                        null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error onMethodCall startRecordScreen: ${e.message}", e)
                    pendingResult = null
                    result.success(false)
                }
            }

            "stopRecordScreen" -> {
                try {
                    serviceConnection?.let {
                        try {
                            appContext.unbindService(it)
                        } catch (_: Exception) {
                        }
                    }
                    serviceConnection = null
                    ForegroundService.stopService(pluginBinding!!.applicationContext)
                    if (mMediaRecorder != null) {
                        val path = stopRecordScreen()
                        result.success(path)
                    } else {
                        result.success("")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stopRecordScreen error: ${e.message}", e)
                    result.success("")
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun calculateResolution(metrics: DisplayMetrics) {
        mDisplayHeight = metrics.heightPixels
        mDisplayWidth = metrics.widthPixels

        var maxRes = 1280.0
        if (metrics.scaledDensity >= 3.0f) {
            maxRes = 1920.0
        }
        if (metrics.widthPixels > metrics.heightPixels) {
            var rate = metrics.widthPixels / maxRes
            if (rate > 1.5) {
                rate = 1.5
            }
            mDisplayWidth = maxRes.toInt()
            mDisplayHeight = (metrics.heightPixels / rate).toInt()
        } else {
            var rate = metrics.heightPixels / maxRes
            if (rate > 1.5) {
                rate = 1.5
            }
            mDisplayHeight = maxRes.toInt()
            mDisplayWidth = (metrics.widthPixels / rate).toInt()
        }
    }

    private fun cacheDir(): String {
        val context = pluginBinding!!.applicationContext
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath
        } else {
            context.cacheDir.absolutePath
        }
    }

    private fun startRecordScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mMediaRecorder = MediaRecorder(pluginBinding!!.applicationContext)
        } else {
            @Suppress("DEPRECATION")
            mMediaRecorder = MediaRecorder()
        }

        val base = cacheDir()
        val name = videoName ?: "recording"
        mFileName = "$base/$name.mp4"
        mTempVideoPath = if (useInternalAudio) "$base/$name-video.mp4" else mFileName
        mTempAudioPath = if (useInternalAudio) "$base/$name-audio.mp4" else null

        // Ensure clean temp outputs.
        listOfNotNull(mFileName, mTempVideoPath, mTempAudioPath).forEach { path ->
            val f = File(path)
            if (f.exists()) {
                f.delete()
            }
        }

        val outputPath = mTempVideoPath ?: mFileName
            ?: throw IOException("Missing output path")

        mMediaRecorder?.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (recordAudio == true && !useInternalAudio) {
            // Pre-Q fallback: microphone only.
            mMediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
            mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mMediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        } else {
            mMediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        }
        mMediaRecorder?.setOutputFile(outputPath)
        mMediaRecorder?.setVideoSize(mDisplayWidth, mDisplayHeight)
        mMediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder?.setVideoEncodingBitRate(5 * mDisplayWidth * mDisplayHeight)
        mMediaRecorder?.setVideoFrameRate(30)
        mMediaRecorder?.prepare()
        mMediaRecorder?.start()
    }

    private fun startInternalAudioIfNeeded() {
        if (!useInternalAudio) {
            return
        }
        val projection = mMediaProjection
        val audioPath = mTempAudioPath
        if (projection == null || audioPath == null) {
            Log.w(TAG, "Internal audio skipped — missing projection/path")
            useInternalAudio = false
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recorder = InternalAudioRecorder(audioPath, projection)
                recorder.start()
                internalAudioRecorder = recorder
            }
        } catch (e: Exception) {
            Log.e(TAG, "Internal audio start failed, continuing video-only: ${e.message}", e)
            useInternalAudio = false
            internalAudioRecorder = null
        }
    }

    private fun stopRecordScreen(): String {
        var videoPath = mTempVideoPath ?: mFileName ?: ""
        try {
            mMediaRecorder?.stop()
            mMediaRecorder?.reset()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder stop error: ${e.message}", e)
        } finally {
            stopScreenSharing()
            try {
                mMediaRecorder?.release()
            } catch (_: Exception) {
            }
            mMediaRecorder = null
        }

        val audioRecorder = internalAudioRecorder
        internalAudioRecorder = null
        try {
            audioRecorder?.end()
        } catch (e: Exception) {
            Log.e(TAG, "Internal audio end error: ${e.message}", e)
        }

        val finalPath = mFileName ?: videoPath
        val audioPath = mTempAudioPath
        if (useInternalAudio &&
            !audioPath.isNullOrEmpty() &&
            File(videoPath).exists() &&
            File(audioPath).exists() &&
            File(audioPath).length() > 0
        ) {
            try {
                RecordingMuxer(
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                    finalPath,
                    videoPath,
                    audioPath,
                ).mux()
                // Prefer muxed output; delete temps.
                if (videoPath != finalPath) {
                    File(videoPath).delete()
                }
                File(audioPath).delete()
                videoPath = finalPath
            } catch (e: Exception) {
                Log.e(TAG, "Mux failed, returning video-only: ${e.message}", e)
                if (videoPath != finalPath) {
                    try {
                        File(videoPath).copyTo(File(finalPath), overwrite = true)
                        File(videoPath).delete()
                        videoPath = finalPath
                    } catch (_: Exception) {
                    }
                }
                File(audioPath).delete()
            }
        } else if (useInternalAudio && videoPath != finalPath && File(videoPath).exists()) {
            try {
                File(videoPath).copyTo(File(finalPath), overwrite = true)
                File(videoPath).delete()
                videoPath = finalPath
            } catch (_: Exception) {
            }
            audioPath?.let { File(it).delete() }
        }

        useInternalAudio = false
        mTempVideoPath = null
        mTempAudioPath = null
        return videoPath
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        return try {
            mMediaProjection?.createVirtualDisplay(
                "MainActivity",
                mDisplayWidth,
                mDisplayHeight,
                mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder?.surface,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay err: ${e.message}", e)
            null
        }
    }

    private fun stopScreenSharing() {
        try {
            mVirtualDisplay?.release()
        } catch (_: Exception) {
        }
        mVirtualDisplay = null
        if (mMediaProjection != null && mMediaProjectionCallback != null) {
            try {
                mMediaProjection?.unregisterCallback(mMediaProjectionCallback!!)
            } catch (_: Exception) {
            }
            try {
                mMediaProjection?.stop()
            } catch (_: Exception) {
            }
            mMediaProjection = null
        }
    }

    private fun cleanupAfterFailure() {
        try {
            internalAudioRecorder?.end()
        } catch (_: Exception) {
        }
        internalAudioRecorder = null
        try {
            mMediaRecorder?.reset()
            mMediaRecorder?.release()
        } catch (_: Exception) {
        }
        mMediaRecorder = null
        stopScreenSharing()
        useInternalAudio = false
        ForegroundService.stopService(pluginBinding!!.applicationContext)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        val channel = MethodChannel(pluginBinding!!.binaryMessenger, "flutter_screen_recording")
        channel.setMethodCallHandler(this)
        activityBinding!!.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {}

    inner class MediaProjectionCallback : MediaProjection.Callback() {
        override fun onStop() {
            try {
                mMediaRecorder?.reset()
            } catch (_: Exception) {
            }
            mMediaProjection = null
            stopScreenSharing()
        }
    }

    companion object {
        private const val TAG = "ScreenRecordingPlugin"
    }
}
