package com.sushil.audiomaster

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
    private lateinit var recordBtn: Button
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
    private var isRecording = false
    private var monitorThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var wavOut: FileOutputStream? = null
    private var wavFile: File? = null
    private var recordedBytes = 0

    private val sampleRate = 44100
    private val permissionCode = 1001
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile private var gain = 1.2f
    @Volatile private var gateDb = -80f
    @Volatile private var bassDb = 0f
    @Volatile private var trebleDb = 0f
    @Volatile private var echoMix = 0f
    @Volatile private var reverbMix = 0f
    @Volatile private var reverbRoom = 0.5f
    @Volatile private var reverbDamp = 0.5f
    private val preamp = 2.2f

    private var bassState = 0f
    private var trebleState = 0f
    private var gateGain = 1f
    private val echoDelaySamples = sampleRate / 4
    private val echoBuffer = FloatArray(echoDelaySamples)
    private var echoIndex = 0

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
        recordBtn = findViewById(R.id.recordBtn)
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

        gainBar.progress = 120
        gainVal.text = "120%"
        // গেট প্রায় বন্ধ = পাম্পিং কম
        gateBar.progress = 0
        gateVal.text = "-80 dB"

        val types = arrayOf("ছোট ঘর", "হল", "ক্যাথিড্রাল", "প্লেট")
        reverbType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
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
            if (!isRecording) {
                statusText.text = if (isMuted) "মাইক মিউট আছে" else "মাইক চালু আছে"
                statusText.contentDescription = statusText.text
            }
        }
        recordBtn.setOnClickListener {
            if (!isMicOn) return@setOnClickListener
            if (!isRecording) beginWavCapture() else endWavCapture(true)
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

    private fun beginWavCapture() {
        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            if (dir == null) {
                Toast.makeText(this, "সেভ ফোল্ডার পাওয়া যায়নি", Toast.LENGTH_LONG).show()
                return
            }
            if (!dir.exists()) dir.mkdirs()
            val name = "AudioMaster_" + System.currentTimeMillis() + ".wav"
            wavFile = File(dir, name)
            wavOut = FileOutputStream(wavFile)
            wavOut!!.write(ByteArray(44))
            recordedBytes = 0
            isRecording = true
            recordBtn.text = "রেকর্ড বন্ধ"
            statusText.text = "রেকর্ডিং চলছে"
            statusText.contentDescription = "রেকর্ডিং চলছে"
        } catch (e: Exception) {
            Toast.makeText(this, "রেকর্ড শুরু হয়নি: " + e.message, Toast.LENGTH_LONG).show()
            isRecording = false
        }
    }

    private fun endWavCapture(showToast: Boolean) {
        if (!isRecording) return
        isRecording = false
        try {
            wavOut?.flush()
            wavOut?.close()
        } catch (_: Exception) {
        }
        wavOut = null
        try {
            val f = wavFile
            if (f != null && f.exists()) {
                writeWavHeader(f, recordedBytes)
                if (showToast) {
                    Toast.makeText(this, "সেভ: " + f.absolutePath, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            if (showToast) {
                Toast.makeText(this, "সেভ এরর: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
        recordBtn.text = "রেকর্ড শুরু"
        if (isMicOn) {
            statusText.text = if (isMuted) "মাইক মিউট আছে" else "মাইক চালু আছে"
            statusText.contentDescription = statusText.text
        }
    }

    private fun writeIntLE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
        out[offset + 2] = ((value shr 16) and 0xff).toByte()
        out[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShortLE(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun writeWavHeader(file: File, dataBytes: Int) {
        val h = ByteArray(44)
        h[0] = 'R'.code.toByte()
        h[1] = 'I'.code.toByte()
        h[2] = 'F'.code.toByte()
        h[3] = 'F'.code.toByte()
        writeIntLE(h, 4, 36 + dataBytes)
        h[8] = 'W'.code.toByte()
        h[9] = 'A'.code.toByte()
        h[10] = 'V'.code.toByte()
        h[11] = 'E'.code.toByte()
        h[12] = 'f'.code.toByte()
        h[13] = 'm'.code.toByte()
        h[14] = 't'.code.toByte()
        h[15] = ' '.code.toByte()
        writeIntLE(h, 16, 16)
        writeShortLE(h, 20, 1)
        writeShortLE(h, 22, 1)
        writeIntLE(h, 24, sampleRate)
        writeIntLE(h, 28, sampleRate * 2)
        writeShortLE(h, 32, 2)
        writeShortLE(h, 34, 16)
        h[36] = 'd'.code.toByte()
        h[37] = 'a'.code.toByte()
        h[38] = 't'.code.toByte()
        h[39] = 'a'.code.toByte()
        writeIntLE(h, 40, dataBytes)
        val raf = RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(h)
        raf.close()
    }

    private fun writePcmToWav(samples: ShortArray, count: Int) {
        val out = wavOut ?: return
        val bytes = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[j++] = (v and 0xff).toByte()
            bytes[j++] = ((v shr 8) and 0xff).toByte()
        }
        out.write(bytes)
        recordedBytes += bytes.size
    }

    private fun pickMicSource(): Int {
        return if (Build.VERSION.SDK_INT >= 24) {
            try {
                MediaRecorder.AudioSource.UNPROCESSED
            } catch (_: Exception) {
                MediaRecorder.AudioSource.MIC
            }
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    private fun startMic() {
        if (isMicOn) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL

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

            var source = pickMicSource()
            audioRecord = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                try { audioRecord?.release() } catch (_: Exception) {}
                source = MediaRecorder.AudioSource.MIC
                audioRecord = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

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
            gateGain = 1f
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
            recordBtn.isEnabled = true
            recordBtn.text = "রেকর্ড শুরু"
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

                    // নরম গেট: হঠাৎ কাটে না
                    val target = if (rms >= gateLin) 1f else 0.15f
                    gateGain += (target - gateGain) * 0.08f

                    val g = gain * preamp
                    val bDb = bassDb
                    val tDb = trebleDb
                    val eMix = echoMix
                    val rMix = reverbMix
                    val gg = gateGain
                    val bassA = 0.05f + abs(bDb) / 12f * 0.05f
                    val trebleA = 0.05f + abs(tDb) / 12f * 0.05f
                    val bassG = 10f.pow(bDb / 20f)
                    val trebleG = 10f.pow(tDb / 20f)

                    for (i in 0 until read) {
                        var x = buf[i] / 32768f
                        x *= g
                        x *= gg

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
                    if (isRecording) {
                        writePcmToWav(buf, read)
                    }

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
        if (isRecording) endWavCapture(true)
        isMicOn = false
        try { monitorThread?.join(500) } catch (_: Exception) {}
        monitorThread = null
        releaseAudio()
        micBtn.text = "মাইক চালু করুন"
        muteBtn.isEnabled = false
        muteBtn.text = "মিউট"
        recordBtn.isEnabled = false
        recordBtn.text = "রেকর্ড শুরু"
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
