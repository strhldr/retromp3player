package com.retrosouldev.retromp3player

import android.content.*
import android.net.Uri
import android.os.*
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // ---- fast seek ----
    private val FF_STEP_MS = 10_000
    private val REPEAT_INTERVAL_MS = 120L
    private val LONG_PRESS_DELAY_MS = 300L
    private var isHolding = false
    private val ffHandler = Handler(Looper.getMainLooper())
    private var ffRepeater: Runnable? = null
    private var nextLongPress = false
    private var prevLongPress = false

    // ---- Equalizer UI ----
    private val BANDS_HZ = intArrayOf(60, 230, 910, 3600, 14000)
    private val EQ_MIN_MB = -1500
    private val EQ_MAX_MB = 1500
    private lateinit var eqContainer: LinearLayout
    private val bandSeekBars = mutableListOf<SeekBar>()

    // ---- Player state / refs ----
    private var durationMs = 0
    private var userSeeking = false

    private lateinit var txtNowPlaying: TextView
    private lateinit var btnOpen: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var swEq: Switch

    // progress updates from Service
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MusicService.ACTION_PROGRESS -> {
                    val pos = intent.getIntExtra(MusicService.EXTRA_POSITION, 0)
                    val dur = intent.getIntExtra(MusicService.EXTRA_DURATION, 0)
                    durationMs = dur
                    if (!userSeeking) {
                        seekBar.max = dur.coerceAtLeast(1)
                        seekBar.progress = pos.coerceIn(0, seekBar.max)
                    }
                }
                MusicService.ACTION_COMPLETED -> seekBar.progress = 0
            }
        }
    }

    // pick multiple audio files
    private val openAudio = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_SET_PLAYLIST
                putParcelableArrayListExtra(
                    MusicService.EXTRA_URI_LIST,
                    ArrayList<Uri>(uris)
                )
            })
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_START
            })
            txtNowPlaying.text = "Playlist loaded (${uris.size} tracks)"
            enableControls(true)
        }
    }

    // ---------- onCreate ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtNowPlaying = findViewById(R.id.txtNowPlaying)
        btnOpen       = findViewById(R.id.btnOpen)
        btnPrev       = findViewById(R.id.btnPrev)
        btnPlay       = findViewById(R.id.btnPlay)
        btnPause      = findViewById(R.id.btnPause)
        btnNext       = findViewById(R.id.btnNext)
        seekBar       = findViewById(R.id.seekBar)
        swEq          = findViewById(R.id.swEq)
        eqContainer   = findViewById(R.id.eqContainer)

        enableControls(false)
        buildEqSliders()

        // open files
        btnOpen.setOnClickListener { openAudio.launch(arrayOf("audio/*")) }

        // play/pause/next/prev
        btnPlay.setOnClickListener {
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY
            })
        }
        btnPause.setOnClickListener {
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PAUSE
            })
        }
        btnNext.setOnClickListener {
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_NEXT
            })
        }
        btnPrev.setOnClickListener {
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PREV
            })
        }

        // long press → fast seek
        btnNext.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    nextLongPress = false
                    ffHandler.postDelayed({
                        if (isHolding) {
                            nextLongPress = true
                            startFastSeek(+FF_STEP_MS)
                            ffRepeater = object : Runnable {
                                override fun run() {
                                    if (isHolding) {
                                        startFastSeek(+FF_STEP_MS)
                                        ffHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                                    }
                                }
                            }
                            ffHandler.postDelayed(ffRepeater!!, REPEAT_INTERVAL_MS)
                        }
                    }, LONG_PRESS_DELAY_MS)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasLong = nextLongPress
                    stopFastSeek()
                    nextLongPress = false
                    isHolding = false
                    wasLong // true = event consumed, click არ გაეშვება
                }
                else -> false
            }
        }

        btnPrev.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    prevLongPress = false
                    ffHandler.postDelayed({
                        if (isHolding) {
                            prevLongPress = true
                            startFastSeek(-FF_STEP_MS)
                            ffRepeater = object : Runnable {
                                override fun run() {
                                    if (isHolding) {
                                        startFastSeek(-FF_STEP_MS)
                                        ffHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                                    }
                                }
                            }
                            ffHandler.postDelayed(ffRepeater!!, REPEAT_INTERVAL_MS)
                        }
                    }, LONG_PRESS_DELAY_MS)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasLong = prevLongPress
                    stopFastSeek()
                    prevLongPress = false
                    isHolding = false
                    wasLong
                }
                else -> false
            }
        }

        // EQ toggle
        swEq.setOnCheckedChangeListener { _, isChecked ->
            startService(Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_TOGGLE_EQ
                putExtra(MusicService.EXTRA_EQ_ENABLED, isChecked)
            })
            bandSeekBars.forEach { it.isEnabled = isChecked }
        }

        // seekbar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                startService(Intent(this@MainActivity, MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra(MusicService.EXTRA_SEEK_TO, sb.progress.coerceIn(0, durationMs))
                })
                userSeeking = false
            }
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
        })
    }

    // ---------- helpers ----------
    private fun buildEqSliders() {
        eqContainer.removeAllViews()
        bandSeekBars.clear()

        for ((index, hz) in BANDS_HZ.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val label = TextView(this).apply {
                text = "$hz Hz"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val sb = SeekBar(this).apply {
                max = EQ_MAX_MB - EQ_MIN_MB
                progress = 0 - EQ_MIN_MB
                isEnabled = swEq.isChecked
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            }

            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
                override fun onProgressChanged(s: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val levelMb = EQ_MIN_MB + value
                    startService(Intent(this@MainActivity, MusicService::class.java).apply {
                        action = MusicService.ACTION_EQ_SET_BAND
                        putExtra(MusicService.EXTRA_EQ_BAND_INDEX, index)
                        putExtra(MusicService.EXTRA_EQ_LEVEL_MB, levelMb)
                    })
                }
            })

            bandSeekBars += sb
            row.addView(label)
            row.addView(sb)
            eqContainer.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startFastSeek(delta: Int) {
        startService(Intent(this, MusicService::class.java).apply {
            action = MusicService.ACTION_SEEK_REL
            putExtra(MusicService.EXTRA_SEEK_DELTA, delta)
        })
    }

    private fun stopFastSeek() {
        isHolding = false
        ffRepeater?.let { ffHandler.removeCallbacks(it) }
        ffHandler.removeCallbacksAndMessages(null)
    }

    private fun enableControls(enable: Boolean) {
        listOf(btnPlay, btnPause, btnNext, btnPrev, seekBar).forEach { it.isEnabled = enable }
        bandSeekBars.forEach { it.isEnabled = swEq.isChecked && enable }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MusicService.ACTION_PROGRESS)
            addAction(MusicService.ACTION_COMPLETED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(progressReceiver) } catch (_: Exception) {}
    }
}
