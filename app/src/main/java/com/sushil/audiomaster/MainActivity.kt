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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var micBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var gainBar: SeekBar
    private lateinit var gateBar: SeekBar
    private lateinit var bassBar: SeekBar
    private lateinit var trebleBar: SeekBar
    private lateinit var echoBar: SeekBar
    private lateinit var gainVal: TextView
    private lateinit var gateVal: TextView
    private lateinit var bassVal: TextView
    private lateinit var trebleVal: TextView
    private lateinit var echoVal: TextView

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

    @Volatile private var gain = 0.8f
    @Volatile private var gateDb = -40f
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var echoMix = 0f

    // simple shelf filter state
    private var bassState = 0f
    private var trebleState = 0f

    // echo delay \~250ms
    private val echoDelaySamples = sampleRate / 4
    private val echoBuffer = FloatArray(echoDelaySamples)
    private var echoIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        levelText = findViewById(R.id.levelText)
        micBtn = findViewById(R.id.micBtn)
        muteBtn = findViewById(R.id.muteBtn)
        gainBar = findViewById(R.id.gainBar)
        gateBar = findViewById(R.id.gateBar)
        bassBar = findViewById(R.id.bassBar)
        trebleBar = findViewById(R.id.trebleBar)
        echoBar = findViewById(R.id.echoBar)
        gainVal = findViewById(R.id.gainVal)
        gateVal = findViewById(R.id.gateVal)
        bassVal = findViewById(R.id.bassVal)
        trebleVal = findViewById(R.id.trebleVal)
        echoVal = findViewById(R.id.echoVal)

        micBtn.setOnClickListener {
            if (!isMicOn) startMicFlow() else stopMic()
        }

        muteBtn.setOnClickListener {
            if (!isMicOn) return@setOnClickListener
            isMuted = !isMuted
            muteBtn.text = if (isMuted) "আনমিউট" else "মিউট"
            statusText.text = if (isMuted) "মাইক মিউট আছে" else "মাইক চালু আছে"
            statusText.contentDescription = statusText.text
        }

        gainBar.setOnSeekBarChangeListener(simpleSeek { p ->
            gain = p / 100f
            gainVal.text = "$p%"
            gainVal.contentDescription = "গেইন $p শতাংশ"
        })
        gateBar.setOnSeekBarChangeListener(simpleSeek { p ->
            // progress 0..70 => -80..-10 dB
            gateDb = -80f + p
            gateVal.text = "${gateDb.toInt()} dB"
            gateVal.contentDescription = "গেট ${gateDb.toInt()} ডিবি"
        })
        bassBar.setOnSeekBarChangeListener(simpleSeek { p ->
            bassDb = (p - 12).toFloat()
            val t = if (bassDb > 0) "+\( {bassDb.toInt()} dB" else " \){bassDb.toInt()} dB"
            bassVal.text = t
            bassVal.contentDescription = "বাস $t"
        })
        trebleBar.setOnSeekBarChangeListener(simpleSeek { p ->
            trebleDb = (p - 12).toFloat()
            val t = if (trebleDb > 0) "+\( {trebleDb.toInt()} dB" else " \){trebleDb.toInt()} dB"
            trebleVal.text = t
            trebleVal.contentDescription = "ট্রেবল $t"
        })
        echoBar.setOnSeekBarChangeListener(simpleSeek { p ->
            echoMix = p / 100f
            echoVal.text = "$p%"
            echoVal.contentDescription = "ইকো $p শতাংশ"
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    private fun startMicFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), permissionCode
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
            ) return

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelIn, encoding, bufferSize
            )
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate, channelOut, encoding, bufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED ||
                audioTrack?.state != AudioTrack.STATE_INITIALIZED
            ) {
                Toast.makeText(this, "অডিও চালু করা যায়নি", Toast.LENGTH_LONG).show()
                releaseAudio()
                return
            }

            // reset FX state
            bassState = 0f
            trebleState = 0f
            echoIndex = 0
            for (i in echoBuffer.indices) echoBuffer[i] = 0f

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
                val gateLin = 10f.pow(gateDb / 20f)
                while (isMicOn) {
                    val rec = audioRecord ?: break
                    val track = audioTrack ?: break
                    val read = rec.read(buf, 0, buf.size)
                    if (read <= 0) continue

                    // RMS for gate + meter
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val x = buf[i] / 32768f
                        sumSq += (x * x).toDouble()
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    val open = rms >= gateLin

                    val g = gain
                    val bDb = bassDb
                    val tDb = trebleDb
                    val eMix = echoMix
                    val bassA = shelfAlpha(bDb)
                    val trebleA = shelfAlpha(tDb)
                    val bassG = 10f.pow(bDb / 20f)
                    val trebleG = 10f.pow(tDb / 20f)

                    for (i in 0 until read) {
                        var x = buf[i] / 32768f
                        x *= g
                        if (!open) x = 0f

                        // simple low shelf-ish
                        bassState += bassA * (x - bassState)
                        x = x + (bassState * (bassG - 1f))

                        // simple high emphasis
                        val high = x - trebleState
                        trebleState += trebleA * (x - trebleState)
                        x = x + high * (trebleG - 1f) * 0.5f

                        // echo
                        val delayed = echoBuffer[echoIndex]
                        val y = x + delayed * eMix
                        echoBuffer[echoIndex] = x + delayed * 0.35f * eMix
                        echoIndex++
                        if (echoIndex >= echoDelaySamples) echoIndex = 0

                        var out = if (isMuted) 0f else y
                        if (out > 0.99f) out = 0.99f
                        if (out < -0.99f) out = -0.99f
                        buf[i] = (out * 32767f).toInt().toShort()
                    }

                    track.write(buf, 0, read)

                    val db = if (rms < 0.0001f) -60.0 else (20.0 * log10(rms.toDouble())).coerceIn(-60.0, 0.0)
                    val pct = (((db + 60) / 60.0) * 100).toInt()
                    uiHandler.post {
                        levelText.text = "লেভেল: \( pct% ( \){db.toInt()} dB)"
                        levelText.contentDescription = levelText.text
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "এরর: ${e.message}", Toast.LENGTH_LONG).show()
            stopMic()
        }
    }

    private fun shelfAlpha(db: Float): Float {
        // mild smoothing factor
        return 0.05f + (kotlin.math.abs(db) / 12f) * 0.05f
    }

    private fun stopMic() {
        isMicOn = false
        try { monitorThread?.join(500) } catch (_: Exception) {}
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
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
    }

    override fun onDestroy() {
        stopMic()
        super.onDestroy()
    }
}
