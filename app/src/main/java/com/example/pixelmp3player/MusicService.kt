package com.retrosouldev.retromp3player

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class MusicService : Service() {

    companion object {
        // --- broadcasts to Activity ---
        const val ACTION_PROGRESS   = "ACTION_PROGRESS"
        const val ACTION_COMPLETED  = "ACTION_COMPLETED"
        const val EXTRA_POSITION    = "EXTRA_POSITION"
        const val EXTRA_DURATION    = "EXTRA_DURATION"

        // --- playlist ---
        const val ACTION_SET_PLAYLIST = "ACTION_SET_PLAYLIST"
        const val EXTRA_URI_LIST      = "EXTRA_URI_LIST"   // ArrayList<Uri>
        const val EXTRA_URI           = "EXTRA_URI"        // single Uri (optional)

        // --- playback controls ---
        const val ACTION_START = "ACTION_START"
        const val ACTION_PLAY  = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_NEXT  = "ACTION_NEXT"
        const val ACTION_PREV  = "ACTION_PREV"
        const val ACTION_STOP  = "ACTION_STOP"

        // seeking
        const val ACTION_SEEK       = "ACTION_SEEK"
        const val EXTRA_SEEK_TO     = "EXTRA_SEEK_TO"
        const val ACTION_SEEK_REL   = "ACTION_SEEK_REL"
        const val EXTRA_SEEK_DELTA  = "EXTRA_SEEK_DELTA"

        // equalizer
        const val ACTION_TOGGLE_EQ     = "ACTION_TOGGLE_EQ"
        const val ACTION_EQ_SET_BAND   = "ACTION_EQ_SET_BAND"
        const val EXTRA_EQ_ENABLED     = "EXTRA_EQ_ENABLED"
        const val EXTRA_EQ_BAND_INDEX  = "EXTRA_EQ_BAND_INDEX"
        const val EXTRA_EQ_LEVEL_MB    = "EXTRA_EQ_LEVEL_MB"

        // shuffle / repeat
        const val ACTION_SET_SHUFFLE = "ACTION_SET_SHUFFLE"
        const val EXTRA_SHUFFLE_ON   = "EXTRA_SHUFFLE_ON"
        const val ACTION_CYCLE_REPEAT = "ACTION_CYCLE_REPEAT"
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }

    // --- state ---
    private val queue = mutableListOf<Uri>()
    private var index = 0

    private var mp: MediaPlayer? = null
    private var eq: Equalizer? = null
    private var eqEnabled = false

    private var repeatMode = REPEAT_OFF
    private var shuffleOn = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            mp?.let { player ->
                if (player.isPlaying) {
                    sendBroadcast(Intent(ACTION_PROGRESS).apply {
                        putExtra(EXTRA_POSITION, player.currentPosition)
                        putExtra(EXTRA_DURATION, player.duration)
                    })
                }
            }
            progressHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        progressHandler.post(progressTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_PLAYLIST -> {
                val list: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(EXTRA_URI_LIST, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(EXTRA_URI_LIST)
                }
                if (!list.isNullOrEmpty()) {
                    queue.clear()
                    queue.addAll(list)
                    index = 0
                }
            }

            ACTION_START -> {
                if (queue.isNotEmpty()) startAt(index)
                else intent.getStringExtra(EXTRA_URI)?.let { startFromUri(Uri.parse(it)) }
            }

            ACTION_PLAY  -> { if (mp == null && queue.isNotEmpty()) startAt(index) else mp?.start() }
            ACTION_PAUSE -> mp?.pause()

            ACTION_NEXT -> {
                if (queue.isNotEmpty()) { index = nextIndex(); startAt(index) }
            }
            ACTION_PREV -> {
                if (queue.isNotEmpty()) { index = prevIndex(); startAt(index) }
            }

            ACTION_SEEK -> {
                val to = intent.getIntExtra(EXTRA_SEEK_TO, 0)
                mp?.seekTo(to)
            }
            ACTION_SEEK_REL -> {
                val d = intent.getIntExtra(EXTRA_SEEK_DELTA, 0)
                mp?.let { p -> p.seekTo((p.currentPosition + d).coerceIn(0, p.duration)) }
            }

            ACTION_TOGGLE_EQ -> {
                eqEnabled = intent.getBooleanExtra(EXTRA_EQ_ENABLED, false)
                eq?.enabled = eqEnabled
            }
            ACTION_EQ_SET_BAND -> {
                val band = intent.getIntExtra(EXTRA_EQ_BAND_INDEX, -1)
                val levelMb = intent.getIntExtra(EXTRA_EQ_LEVEL_MB, 0)
                setBandLevel(band, levelMb)
            }

            ACTION_SET_SHUFFLE -> {
                shuffleOn = intent.getBooleanExtra(EXTRA_SHUFFLE_ON, false)
            }
            ACTION_CYCLE_REPEAT -> {
                repeatMode = when (repeatMode) {
                    REPEAT_OFF -> REPEAT_ALL
                    REPEAT_ALL -> REPEAT_ONE
                    else       -> REPEAT_OFF
                }
            }

            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    // --- helpers ---

    private fun startAt(i: Int) {
        if (i !in queue.indices) return
        startFromUri(queue[i])
    }

    private fun startFromUri(uri: Uri) {
        stopAll()

        mp = MediaPlayer().apply {
            setDataSource(this@MusicService, uri)
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                when (repeatMode) {
                    REPEAT_ONE -> startAt(index)
                    else -> {
                        if (queue.isNotEmpty()) {
                            index = nextIndex()
                            startAt(index)
                        }
                        sendBroadcast(Intent(ACTION_COMPLETED))
                    }
                }
            }
            prepare()
        }

        eq = Equalizer(0, mp!!.audioSessionId).apply { enabled = eqEnabled }
    }

    private fun nextIndex(): Int {
        if (queue.isEmpty()) return 0
        return if (shuffleOn) {
            // აირჩიე შემთხვევითი ინდექსი, მაგრამ თუ მხოლოდ ერთი ტრეკია — იგივე დარჩეს
            val candidates = queue.indices.filter { it != index }
            if (candidates.isEmpty()) index else candidates.random()
        } else {
            (index + 1) % queue.size
        }
    }

    private fun prevIndex(): Int {
        if (queue.isEmpty()) return 0
        return if (shuffleOn) {
            val candidates = queue.indices.filter { it != index }
            if (candidates.isEmpty()) index else candidates.random()
        } else {
            if (index - 1 < 0) queue.lastIndex else index - 1
        }
    }

    private fun setBandLevel(bandIndex: Int, levelMb: Int) {
        val e = eq ?: return
        if (bandIndex !in 0 until e.numberOfBands) return

        val range = e.bandLevelRange
        val min = range[0].toInt()
        val max = range[1].toInt()
        val clamped = levelMb.coerceIn(min, max)
        e.setBandLevel(bandIndex.toShort(), clamped.toShort())
    }

    private fun stopAll() {
        try { eq?.release() } catch (_: Exception) {}
        eq = null
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    override fun onDestroy() {
        progressHandler.removeCallbacksAndMessages(null)
        stopAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
