package com.sushil.audiomaster

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var micBtn: Button
    private lateinit var muteBtn: Button

    private var isMicOn = false
    private var isMuted = false
    private var monitorThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val sampleRate = 44100
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val permissionCode = 1001
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var gain = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        levelText = findViewById(R.id.levelText)
        micBtn = findViewById(R.id.micBtn)
        muteBtn = findViewById(R.id.muteBtn)

        micBtn.setOnClickListener {
            if (!isMicOn) {
                startMicFlow()
            } else {
                stopMic()
            }
        }

        muteBtn.setOnClickListener {
            if (!isMicOn) return@setOnClickListener
            isMuted = !isMuted
            muteBtn.text = if (isMuted) "আনমিউট" else "মিউট"
            statusText.text = if (isMuted) "মাইক মিউট আছে" else "মাইক চালু আছে"
            statusText.contentDescription = statusText.text
        }
    }

    private fun startMicFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionCode
            )
            return
        }
        startMic()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMic()
            } else {
                Toast.makeText(this, "মাইকের অনুমতি দরকার", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMic() {
        if (isMicOn) return
        try {
            val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
            val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
            val bufferSize = max(minRec, minPlay) * 2

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelIn,
                encoding,
                bufferSize
            )
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelOut,
                encoding,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED ||
                audioTrack?.state != AudioTrack.STATE_INITIALIZED
            ) {
                Toast.makeText(this, "অডিও চালু করা যায়নি", Toast.LENGTH_LONG).show()
                releaseAudio()
                return
            }

            isMicOn = true
            isMuted = false
            micBtn.text = "মাইক বন্ধ করুন"
            muteBtn.isEnabled = true
            muteBtn.text = "মিউট"
            statusText.text = "মাইক চালু আছে"
            statusText.contentDescription = "মাইক চালু আছে"

            audioRecord?.startRecording()
            audioTrack?.play()

            monitorThread = thread(start = true, name = "mic-monitor") {
                val buf = ShortArray(bufferSize / 2)
                while (isMicOn) {
                    val rec = audioRecord ?: break
                    val track = audioTrack ?: break
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            var s = (buf[i] * gain).toInt()
                            if (s > 32767) s = 32767
                            if (s < -32768) s = -32768
                            if (isMuted) s = 0
                            buf[i] = s.toShort()
                            sum += (s * s).toDouble()
                        }
                        track.write(buf, 0, read)
                        val rms = sqrt(sum / read)
                        val db = if (rms < 1) -60.0 else (20.0 * log10(rms / 32768.0)).coerceIn(-60.0, 0.0)
                        val pct = (((db + 60) / 60.0) * 100).toInt()
                        uiHandler.post {
                            levelText.text = "লেভেল: \( pct% ( \){db.toInt()} dB)"
                            levelText.contentDescription = levelText.text
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "এরর: ${e.message}", Toast.LENGTH_LONG).show()
            stopMic()
        }
    }

    private fun stopMic() {
        isMicOn = false
        try {
            monitorThread?.join(500)
        } catch (_: Exception) {
        }
        monitorThread = null
        releaseAudio()
        micBtn.text = "মাইক চালু করুন"
        muteBtn.isEnabled = false
        muteBtn.text = "মিউট"
        statusText.text = "মাইক বন্ধ আছে"
        statusText.contentDescription = "মাইক বন্ধ আছে"
        levelText.text = "লেভেল: --"
        levelText.contentDescription = "লেভেল বন্ধ"
        isMuted = false
    }

    private fun releaseAudio() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        audioTrack = null
    }

    override fun onDestroy() {
        stopMic()
        super.onDestroy()
    }
}
