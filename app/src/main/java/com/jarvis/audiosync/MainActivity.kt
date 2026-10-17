package com.jarvis.audiosync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var face: IronFaceView
    private lateinit var status: TextView
    private lateinit var startMic: Button
    private lateinit var captureApps: Button
    private lateinit var stop: Button
    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private var projection: MediaProjection? = null
    private val projectionRequest = 4102
    private val micRequest = 4103

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(3, 7, 13))
            setPadding(24, 18, 24, 20)
        }
        face = IronFaceView(this)
        root.addView(face, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        status = TextView(this).apply {
            text = "Ready • Choose an audio source"
            textSize = 14f
            setTextColor(Color.rgb(0, 229, 255))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }
        root.addView(status)
        startMic = makeButton("SYNC WITH MICROPHONE") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micRequest)
            } else startMicCapture()
        }
        captureApps = makeButton("SYNC WITH OTHER APPS") {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Approve Android's audio-capture prompt"
            startActivityForResult(mgr.createScreenCaptureIntent(), projectionRequest)
        }
        stop = makeButton("STOP SYNC") { stopCapture(); status.text = "Stopped" }
        root.addView(startMic)
        root.addView(captureApps)
        root.addView(stop)
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(10, 45, 62))
        setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52)
        lp.setMargins(0, 5, 0, 5)
        layoutParams = lp
    }

    private fun startMicCapture() {
        stopCapture()
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            status.text = "Microphone unavailable on this device"
            return
        }
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, 4096)
            )
            audioRecord?.startRecording()
            running = true
            status.text = "Listening to microphone"
            readAudioLoop()
        } catch (e: Exception) {
            status.text = "Could not start microphone: ${e.message ?: "permission/device error"}"
            stopCapture()
        }
    }

    private fun startPlaybackCapture(resultCode: Int, data: Intent) {
        stopCapture()
        if (Build.VERSION.SDK_INT < 29) {
            status.text = "App-audio capture needs Android 10 or newer"
            return
        }
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val sampleRate = 16000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val bufferSize = max(
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT),
                8192
            )
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord?.startRecording()
            running = true
            status.text = "Syncing supported app audio"
            readAudioLoop(stereo = true)
        } catch (e: Exception) {
            status.text = "Audio capture blocked: ${e.message ?: "source app may block capture"}"
            stopCapture()
        }
    }

    private fun readAudioLoop(stereo: Boolean = false) {
        val recorder = audioRecord ?: return
        thread(name = "JARVIS-AudioMeter") {
            val buffer = ShortArray(2048)
            while (running) {
                val count = try { recorder.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                if (count > 0) {
                    var sum = 0.0
                    for (i in 0 until count) {
                        val v = buffer[i].toDouble() / 32768.0
                        sum += v * v
                    }
                    val rms = Math.sqrt(sum / count).toFloat()
                    val level = min(1f, max(0f, (rms * 7.0f)))
                    runOnUiThread { face.setAudioLevel(level) }
                } else if (count < 0) {
                    break
                }
            }
        }
    }

    private fun stopCapture() {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        face.setAudioLevel(0f)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest) {
            if (resultCode == RESULT_OK && data != null) startPlaybackCapture(resultCode, data)
            else status.text = "Audio-capture permission was not granted"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startMicCapture()
        else if (requestCode == micRequest) status.text = "Microphone permission denied"
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

class IronFaceView(context: Context) : View(context) {
    private val cyan = Color.rgb(0, 229, 255)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyan; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 25, 39); style = Paint.Style.FILL }
    @Volatile private var level = 0f
    private var blink = false
    private var lastBlink = System.currentTimeMillis()
    private var blinkUntil = 0L

    fun setAudioLevel(v: Float) {
        level = v
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val scale = min(w / 340f, h / 430f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        val now = System.currentTimeMillis()
        if (now - lastBlink > 3200) { blinkUntil = now + 130; lastBlink = now }
        blink = now < blinkUntil

        // Face silhouette
        glowPaint.color = Color.rgb(0, 70, 91); glowPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(-130f, -165f, 130f, 165f, 42f, 42f, glowPaint)
        fillPaint.color = Color.rgb(8, 16, 27)
        canvas.drawRoundRect(-121f, -156f, 121f, 156f, 38f, 38f, fillPaint)
        linePaint.strokeWidth = 4f
        canvas.drawRoundRect(-121f, -156f, 121f, 156f, 38f, 38f, linePaint)

        // forehead plates
        canvas.drawLine(-65f, -115f, 0f, -132f, linePaint)
        canvas.drawLine(0f, -132f, 65f, -115f, linePaint)
        // Eyes
        val eyeY = -55f
        if (blink) {
            canvas.drawLine(-86f, eyeY, -35f, eyeY, linePaint)
            canvas.drawLine(35f, eyeY, 86f, eyeY, linePaint)
        } else {
            val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; style = Paint.Style.FILL }
            canvas.drawOval(-88f, eyeY - 12f, -32f, eyeY + 8f, eye)
            canvas.drawOval(32f, eyeY - 12f, 88f, eyeY + 8f, eye)
            glowPaint.color = Color.argb(75, 0, 229, 255)
            canvas.drawOval(-99f, eyeY - 22f, -22f, eyeY + 19f, glowPaint)
            canvas.drawOval(22f, eyeY - 22f, 99f, eyeY + 19f, glowPaint)
        }
        // Nose / cheek panels
        canvas.drawLine(0f, -30f, -16f, 15f, linePaint)
        canvas.drawLine(-16f, 15f, 0f, 24f, linePaint)
        canvas.drawLine(0f, 24f, 16f, 15f, linePaint)
        canvas.drawLine(-95f, 25f, -65f, 55f, linePaint)
        canvas.drawLine(95f, 25f, 65f, 55f, linePaint)

        // Audio-reactive mouth
        val mouthOpen = 5f + level * 55f
        val mouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan; style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawRoundRect(-42f, 70f, 42f, 70f + mouthOpen, 10f, 10f, mouthPaint)
        canvas.drawLine(-53f, 62f, -38f, 68f, linePaint)
        canvas.drawLine(53f, 62f, 38f, 68f, linePaint)
        canvas.restore()
        postInvalidateDelayed(50)
    }
}
