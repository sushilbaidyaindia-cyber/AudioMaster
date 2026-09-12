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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs
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
    private lateinit var reverbBar: SeekBar
    private lateinit var reverbType: Spinner
    private lateinit var gainVal: TextView
    private lateinit var gateVal: TextView
    private lateinit var bassVal: TextView
    private lateinit var trebleVal: TextView
    private lateinit var echoVal: TextView
    private lateinit var reverbVal: TextView

    private var isMicOn = false
    private var isMuted = false
    private var monitorThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val sampleRate = 44100
    private val permissionCode = 1001
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var gain = 0.8f
    @Volatile private var gateDb = -40f
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var echoMix = 0f
    @Volatile private var reverbMix = 0f
    @Volatile private var reverbRoom = 0.5f
    @Volatile private var reverbDamp = 0.5f

    private var bassState = 0f
    private var trebleState = 0f

    private val echoDelaySamples = sampleRate / 4
    private val echoBuffer = FloatArray(echoDelaySamples)
    private var echoIndex = 0

    // Freeverb-like comb + allpass
    private val combLens = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassLens = intArrayOf(556, 441, 341, 225)
    private lateinit var combBuf: Array<FloatArray>
    private lateinit var combIdx: IntArray
    private lateinit var combFilter: FloatArray
    private lateinit var allpassBuf: Array<FloatArray>
    private lateinit var allpassIdx: IntArray

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
        reverbBar = findViewById(R.id.reverbBar)
        reverbType = findViewById(R.id.reverbType)
        gainVal = findViewById(R.id.gainVal)
        gateVal = findViewById(R.id.gateVal)
        bassVal = findViewById(R.id.bassVal)
        trebleVal = findViewById(R.id.trebleVal)
        echoVal = findViewById(R.id.echoVal)
        reverbVal = findViewById(R.id.reverbVal)

        initReverbBuffers()

        val types = arrayOf("ছোট ঘর", "হল", "ক্যাথিড্রাল", "প্লেট")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        reverbType.adapter = adapter
        reverbType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                when (position) {
                    0 -> { reverbRoom = 0.3f; reverbDamp = 0.4f }
                    1 -> { reverbRoom = 0.7f; reverbDamp = 0.5f }
                    2 -> { reverbRoom = 0.95f; reverbDamp = 0.3f }
                    3 -> { reverbRoom = 0.5f; reverbDamp = 0.8f }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

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

        gainBar.setOnSeekBarChangeListener(onSeek { p ->
            gain = p / 100f
            gainVal.text = p.toString() + "%"
            gainVal.contentDescription = "গেইন " + p + " শতাংশ"
        })
        gateBar.setOnSeekBarChangeListener(onSeek { p ->
            gateDb = -80f + p
            gateVal.text = gateDb.toInt().toString() + " dB"
            gateVal.contentDescription = "গেট " + gateDb.toInt() + " ডিবি"
        })
        bassBar.setOnSeekBarChangeListener(onSeek { p ->
            bassDb = (p - 12).toFloat()
            val n = bassDb.toInt()
            val t = if (n > 0) "+" + n + " dB" else n.toString() + " dB"
            bassVal.text = t
            bassVal.contentDescription = "বাস " + t
        })
        trebleBar.setOnSeekBarChangeListener(onSeek { p ->
            trebleDb = (p - 12).toFloat()
            val n = trebleDb.toInt()
            val t = if (n > 0) "+" + n + " dB" else n.toString() + " dB"
            trebleVal.text = t
            trebleVal.contentDescription = "ট্রেবল " + t
        })
        echoBar.setOnSeekBarChangeListener(onSeek { p ->
            echoMix = p / 100f
            echoVal.text = p.toString() + "%"
            echoVal.contentDescription = "ইকো " + p + " শতাংশ"
        })
        reverbBar.setOnSeekBarChangeListener(onSeek { p ->
            reverbMix = p / 100f
            reverbVal.text = p.toString() + "%"
            reverbVal.contentDescription = "রিভার্ব " + p + " শতাংশ"
        })
    }

    private fun initReverbBuffers() {
        combBuf = Array(combLens.size) { i -> FloatArray(combLens[i]) }
        combIdx = IntArray(combLens.size)
        combFilter = FloatArray(combLens.size)
        allpassBuf = Array(allpassLens.size) { i -> FloatArray(allpassLens[i]) }
        allpassIdx = IntArray(allpassLens.size)
    }

    private fun processReverb(input: Float): Float {
        var isolated = 0f
        val feedback = 0.28f + reverbRoom * 0.5f
        val damp = reverbDamp
        for (i in combLens.indices) {
            val buf = combBuf[i]
            var idx = combIdx[i]
            val y = buf[idx]
            combFilter[i] = y * (1f - damp) + combFilter[i] * damp
            buf[idx] = input + combFilter[i] * feedback
            idx++
            if (idx >= buf.size) idx = 0
            combIdx[i] = idx
            isolated += y
        }
        isolated /= combLens.size

        var x = isolated
        for (i in allpassLens.indices) {
            val buf = allpassBuf[i]
            var idx = allpassIdx[i]
            val bufOut = buf[idx]
            val z = bufOut - x * 0.5f
            buf[idx] = x + bufOut * 0.5f
            idx++
            if (idx >= buf.size) idx = 0
            allpassIdx[i] = idx
            x = z
        }
        return x
    }

    private fun onSeek(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
            val minRec = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val minPlay = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minRec, minPlay) * 2

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) return

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
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

            bassState = 0f
            trebleState = 0f
            echoIndex = 0
            echoBuffer.fill(0f)
            for (i in combBuf.indices) combBuf[i].fill(0f)
            for (i in allpassBuf.indices) allpassBuf[i].fill(0f)
            combIdx.fill(0)
            allpassIdx.fill(0)
            combFilter.fill(0f)

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
                    if (read <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val x = buf[i] / 32768f
                        sumSq += (x * x).toDouble()
                    }
                    val rms = sqrt(sumSq / read).toFloat()
                    val gateLin = 10f.pow(gateDb / 20f)
                    val open = rms >= gateLin

                    val g = gain
                    val bDb = bassDb
                    val tDb = trebleDb
                    val eMix = echoMix
                    val rMix = reverbMix
                    val bassA = 0.05f + abs(bDb) / 12f * 0.05f
                    val trebleA = 0.05f + abs(tDb) / 12f * 0.05f
                    val bassG = 10f.pow(bDb / 20f)
                    val trebleG = 10f.pow(tDb / 20f)

                    for (i in 0 until read) {
                        var x = buf[i] / 32768f
                        x *= g
                        if (!open) x = 0f

                        bassState += bassA * (x - bassState)
                        x += bassState * (bassG - 1f)

                        val high = x - trebleState
                        trebleState += trebleA * (x - trebleState)
                        x += high * (trebleG - 1f) * 0.5f

                        val delayed = echoBuffer[echoIndex]
                        var y = x + delayed * eMix
                        echoBuffer[echoIndex] = x + delayed * 0.35f * eMix
                        echoIndex++
                        if (echoIndex >= echoDelaySamples) echoIndex = 0

                        if (rMix > 0.001f) {
                            val rev = processReverb(y)
                            y = y * (1f - rMix * 0.7f) + rev * rMix
                        }

                        var out = if (isMuted) 0f else y
                        if (out > 0.99f) out = 0.99f
                        if (out < -0.99f) out = -0.99f
                        buf[i] = (out * 32767f).toInt().toShort()
                    }

                    track.write(buf, 0, read)

                    val db = if (rms < 0.0001f) -60.0 else (20.0 * log10(rms.toDouble())).coerceIn(-60.0, 0.0)
                    val pct = (((db + 60) / 60.0) * 100).toInt()
                    uiHandler.post {
                        levelText.text = "লেভেল: " + pct + "% (" + db.toInt() + " dB)"
                        levelText.contentDescription = levelText.text
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "এরর: " + e.message, Toast.LENGTH_LONG).show()
            stopMic()
        }
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
