package biz.rakendus.mb

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class Track(val name: String, val title: String, val dl: Boolean, val pl: String?, val video: Boolean)

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0B0D10")
    private val FG = Color.parseColor("#7FE9FF")
    private val BTN = Color.parseColor("#1B2733")
    private val BTN_ON = Color.parseColor("#0F8C99")

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val pre = Executors.newSingleThreadExecutor()

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var api: Api

    // views
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var paneList: LinearLayout
    private lateinit var panePlay: LinearLayout
    private lateinit var paneDsp: ScrollView
    private lateinit var chipsBox: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var listView: ListView
    private lateinit var surface: SurfaceView
    private lateinit var tvInfo: TextView
    private lateinit var seek: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnShuf: Button
    private lateinit var btnRep: Button
    private lateinit var btnDsp: Button
    private lateinit var btnNorm: Button
    private lateinit var tvTgt: TextView
    private lateinit var tvDspInfo: TextView
    private lateinit var seekVol: SeekBar
    private val navBtns = ArrayList<Button>()

    // data
    private var tracks: List<Track> = emptyList()
    private var playlistOrder: List<String> = emptyList()
    private var loud: Map<String, DoubleArray> = emptyMap()
    private var settings: JSONObject = JSONObject()
    private var queue: List<Track> = emptyList()
    private var qi = -1
    private var current: Track? = null
    private var mode = 1 // 0 play, 1 downloaded, 2 server, 3 playlist, 4 sound
    private var dlFilter: String? = null
    private var shuffle = false
    private var repeatAll = true
    private var dspOn = true
    private var normOn = true
    private var tgt = -12.0
    private var vol = 100
    private var errCount = 0
    private var planned = -1
    private var userSeeking = false
    private val prefetched = HashSet<String>()

    // player
    private lateinit var player: ExoPlayer
    private val proc = DspProcessor()
    private lateinit var cache: SimpleCache
    private lateinit var cacheFactory: CacheDataSource.Factory
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("mb", Context.MODE_PRIVATE)
        api = Api(prefs.getString("url", "https://mb.rakendus.biz") ?: "https://mb.rakendus.biz", prefs.getString("key", "") ?: "")
        buildUi()
        if (api.key.isEmpty()) askServer() else start()
    }

    // ---------- UI ----------
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mkBtn(text: String, weight: Float, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(FG)
        b.setBackgroundColor(BTN)
        b.textSize = 17f
        b.isAllCaps = false
        b.setPadding(dp(6), dp(10), dp(6), dp(10))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { click() }
        return b
    }

    private fun mkText(text: String, size: Float): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(FG)
        return t
    }

    private fun setOn(b: Button, on: Boolean) { b.setBackgroundColor(if (on) BTN_ON else BTN) }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(dp(8), dp(6), dp(8), dp(6))

        tvTitle = mkText("MB Player", 22f)
        tvTitle.setSingleLine(true)
        tvTitle.setOnLongClickListener { askServer(); true }
        root.addView(tvTitle)
        tvStatus = mkText("", 13f)
        tvStatus.setTextColor(Color.parseColor("#8899AA"))
        tvStatus.setSingleLine(true)
        root.addView(tvStatus)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // list pane
        paneList = LinearLayout(this)
        paneList.orientation = LinearLayout.VERTICAL
        chipsScroll = HorizontalScrollView(this)
        chipsBox = LinearLayout(this)
        chipsBox.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(chipsBox)
        paneList.addView(chipsScroll)
        listView = ListView(this)
        listView.setBackgroundColor(BG)
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < shown.size) { queue = shown; playAt(pos) }
        }
        paneList.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(paneList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // play pane
        panePlay = LinearLayout(this)
        panePlay.orientation = LinearLayout.VERTICAL
        panePlay.gravity = Gravity.CENTER_HORIZONTAL
        surface = SurfaceView(this)
        panePlay.addView(surface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tvInfo = mkText("", 18f)
        tvInfo.gravity = Gravity.CENTER
        panePlay.addView(tvInfo)
        content.addView(panePlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // sound pane
        paneDsp = ScrollView(this)
        val dl = LinearLayout(this)
        dl.orientation = LinearLayout.VERTICAL
        paneDsp.addView(dl)
        val r1 = LinearLayout(this)
        btnDsp = mkBtn("DSP: ON", 1f) { dspOn = !dspOn; pushDsp(); saveSettings(); refreshSound() }
        btnNorm = mkBtn("Ühtlustus: ON", 1f) { normOn = !normOn; pushDsp(); saveSettings(); refreshSound() }
        r1.addView(btnDsp); r1.addView(btnNorm)
        dl.addView(r1)
        val r2 = LinearLayout(this)
        r2.gravity = Gravity.CENTER_VERTICAL
        tvTgt = mkText("", 18f)
        r2.addView(mkBtn("−", 0.5f) { tgt = Math.max(-23.0, tgt - 1.0); pushDsp(); saveSettings(); refreshSound() })
        r2.addView(tvTgt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        r2.addView(mkBtn("+", 0.5f) { tgt = Math.min(-6.0, tgt + 1.0); pushDsp(); saveSettings(); refreshSound() })
        dl.addView(r2)
        dl.addView(mkText("Helitugevus", 16f))
        seekVol = SeekBar(this)
        seekVol.max = 100
        seekVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) { vol = p; applyVol() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { saveSettings() }
        })
        dl.addView(seekVol)
        tvDspInfo = mkText("", 15f)
        dl.addView(tvDspInfo)
        val r3 = LinearLayout(this)
        r3.addView(mkBtn("Võta seaded serverist", 1f) { loadAll() })
        r3.addView(mkBtn("Server / PIN", 1f) { askServer() })
        dl.addView(r3)
        val r4 = LinearLayout(this)
        r4.addView(mkBtn("Tühjenda vahemälu", 1f) { clearCache() })
        dl.addView(r4)
        content.addView(paneDsp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // seek row
        val sr = LinearLayout(this)
        sr.gravity = Gravity.CENTER_VERTICAL
        tvPos = mkText("0:00", 14f)
        tvDur = mkText("0:00", 14f)
        seek = SeekBar(this)
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = player.duration
                    if (d > 0) tvPos.text = fmt(d * p / 1000)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
                val d = player.duration
                if (d > 0 && s != null) player.seekTo(d * s.progress / 1000)
            }
        })
        sr.addView(tvPos)
        sr.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sr.addView(tvDur)
        root.addView(sr)

        // transport row
        val tr = LinearLayout(this)
        tr.addView(mkBtn("◀◀", 1f) { prev() })
        btnPlay = mkBtn("▶", 1.4f) { togglePlay() }
        tr.addView(btnPlay)
        tr.addView(mkBtn("▶▶", 1f) { next(false) })
        btnShuf = mkBtn("Segamini", 1.3f) { shuffle = !shuffle; planned = -1; setOn(btnShuf, shuffle); saveSettings() }
        btnRep = mkBtn("Kordus", 1.3f) { repeatAll = !repeatAll; setOn(btnRep, repeatAll); saveSettings() }
        tr.addView(btnShuf); tr.addView(btnRep)
        root.addView(tr)

        // nav row
        val nr = LinearLayout(this)
        val names = arrayOf("Mängib", "Allalaetud", "Server", "Playlist", "Heli")
        for (i in names.indices) {
            val b = mkBtn(names[i], 1f) { showMode(i) }
            navBtns.add(b)
            nr.addView(b)
        }
        root.addView(nr)
        setContentView(root)
        showMode(1)
    }

    private var shown: List<Track> = emptyList()

    private fun showMode(m: Int) {
        mode = m
        for (i in navBtns.indices) setOn(navBtns[i], i == m)
        panePlay.visibility = if (m == 0) View.VISIBLE else View.GONE
        paneDsp.visibility = if (m == 4) View.VISIBLE else View.GONE
        paneList.visibility = if (m in 1..3) View.VISIBLE else View.GONE
        if (m in 1..3) refreshList()
        if (m == 4) refreshSound()
        if (m == 0) refreshNow()
    }

    private fun refreshList() {
        if (!started) return
        val base: List<Track> = when (mode) {
            1 -> tracks.filter { it.dl && (dlFilter == null || it.pl == dlFilter) }
            2 -> tracks.filter { !it.dl }
            else -> {
                val byName = HashMap<String, Track>()
                for (t in tracks) byName[t.name] = t
                playlistOrder.mapNotNull { byName[it] }
            }
        }
        shown = base
        // chips with list names (downloaded view)
        chipsBox.removeAllViews()
        if (mode == 1) {
            val names = tracks.filter { it.dl && it.pl != null }.map { it.pl!! }.distinct().sorted()
            if (names.isNotEmpty()) {
                val all = mkBtn("Kõik", 0f) { dlFilter = null; refreshList() }
                all.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOn(all, dlFilter == null)
                chipsBox.addView(all)
                for (n in names) {
                    val b = mkBtn(n, 0f) { dlFilter = n; refreshList() }
                    b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOn(b, dlFilter == n)
                    chipsBox.addView(b)
                }
            }
        }
        chipsScroll.visibility = if (chipsBox.childCount > 0) View.VISIBLE else View.GONE
        val cur = current
        val titles = ArrayList<String>()
        for (t in base) titles.add((if (cur != null && cur.name == t.name) "▶ " else "") + (if (t.video) "[video] " else "") + t.title)
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, titles) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(FG)
                v.textSize = 20f
                v.setPadding(dp(12), dp(16), dp(12), dp(16))
                return v
            }
        }
        listView.adapter = ad
        if (base.isEmpty()) tvStatus.text = "Siin pole veel midagi"
    }

    private fun refreshNow() {
        val t = current
        tvInfo.text = if (t == null) "Vali lugu" else t.title
        surface.visibility = if (t != null && t.video) View.VISIBLE else View.GONE
    }

    private fun refreshSound() {
        setOn(btnDsp, dspOn)
        btnDsp.text = if (dspOn) "DSP: ON" else "DSP: BYPASS"
        setOn(btnNorm, normOn)
        btnNorm.text = if (normOn) "Ühtlustus: ON" else "Ühtlustus: OFF"
        tvTgt.text = "Sihttase: " + tgt.toInt() + " LUFS"
        seekVol.progress = vol
        val d = settings.optJSONObject("dsp")
        val sb = StringBuilder()
        if (d != null) {
            val eq = d.optJSONArray("eq")
            sb.append("EQ (dB): ")
            if (eq != null) for (i in 0 until eq.length()) {
                val e = eq.optJSONObject(i)
                if (e != null) sb.append(String.format("%.0f", e.optDouble("g", 0.0))).append(" ")
            }
            sb.append("\nSub ").append(d.optDouble("sub", 0.0)).append(" dB · Harmoonikud ").append(d.optDouble("enh", 0.0)).append(" %")
            sb.append("\nKompressor: ").append(when (d.optInt("cm", 0)) { 0 -> "väljas"; 1 -> "üks riba"; else -> "multiriba (siin ühe ribana)" })
            sb.append(" · Piirik ").append(d.optDouble("lc", -1.0)).append(" dB")
            sb.append("\nSeadeid muuda veebis (mb.rakendus.biz → Heli); need sünkroonitakse siia.")
        } else sb.append("Seaded puuduvad – kasutan vaikimisi.")
        tvDspInfo.text = sb.toString()
    }

    private fun status(s: String) { runOnUiThread { tvStatus.text = s } }

    private fun fmt(ms: Long): String {
        val s = (if (ms < 0) 0 else ms) / 1000
        return "" + (s / 60) + ":" + String.format("%02d", s % 60)
    }

    // ---------- dialogs ----------
    private fun askServer() {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(8), dp(16), dp(8))
        val u = EditText(this)
        u.setText(api.base)
        u.hint = "Server (https://mb.rakendus.biz)"
        u.inputType = InputType.TYPE_TEXT_VARIATION_URI
        val k = EditText(this)
        k.setText(api.key)
        k.hint = "PIN"
        k.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        l.addView(u); l.addView(k)
        AlertDialog.Builder(this)
            .setTitle("Server ja PIN")
            .setView(l)
            .setCancelable(api.key.isNotEmpty())
            .setPositiveButton("Salvesta") { _, _ ->
                api.base = u.text.toString().trim().ifEmpty { "https://mb.rakendus.biz" }
                api.key = k.text.toString().trim()
                prefs.edit().putString("url", api.base).putString("key", api.key).apply()
                if (started) loadAll() else start()
            }
            .show()
    }

    private fun clearCache() {
        bg.execute {
            try {
                for (k in cache.keys.toList()) cache.removeResource(k)
                prefetched.clear()
                status("Vahemälu tühjendatud")
            } catch (e: Throwable) { status("Vahemälu: " + e.message) }
        }
    }

    // ---------- player ----------
    private fun start() {
        if (started) return
        started = true
        cache = SimpleCache(File(cacheDir, "mb"), LeastRecentlyUsedCacheEvictor(400L * 1024 * 1024), StandaloneDatabaseProvider(this))
        cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(20000))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val rf = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(proc))
                    .build()
            }
        }
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 90000, 1500, 3000)
            .setTargetBufferBytes(12 * 1024 * 1024)
            .build()
        player = ExoPlayer.Builder(this, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(lc)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setVideoSurfaceView(surface)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next(true)
                if (state == Player.STATE_READY) errCount = 0
                updatePlayIcon()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayIcon() }
            override fun onPlayerError(error: PlaybackException) { onError(error) }
        })
        ui.post(ticker)
        loadAll()
    }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                if (started) {
                    val d = player.duration
                    val p = player.currentPosition
                    if (!userSeeking) {
                        tvPos.text = fmt(p)
                        if (d > 0) { tvDur.text = fmt(d); seek.progress = (p * 1000 / d).toInt() }
                    }
                }
            } catch (e: Throwable) {}
            ui.postDelayed(this, 500)
        }
    }

    private fun updatePlayIcon() { btnPlay.text = if (player.isPlaying) "❚❚" else "▶" }

    private fun onError(e: PlaybackException) {
        errCount++
        val t = current
        status("Viga: " + (e.message ?: e.errorCodeName) + (if (errCount < 3) " – proovin uuesti" else " – järgmine lugu"))
        if (t == null) return
        if (errCount < 3) {
            val pos = player.currentPosition
            ui.postDelayed({ load(t, pos) }, 1500)
        } else {
            errCount = 0
            ui.postDelayed({ next(true) }, 1500)
        }
    }

    private fun loadAll() {
        status("Laen serverist…")
        bg.execute {
            try {
                val files = JSONArray(api.get("/api/files2"))
                val list = ArrayList<Track>()
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    val n = o.getString("n")
                    val pl = if (o.has("p") && !o.isNull("p")) o.getString("p") else null
                    list.add(Track(n, n.replace(Regex("\\.[^.]+$"), ""), o.optInt("y", 0) == 1, pl, Regex("\\.(mp4|m4v|webm|mov)$", RegexOption.IGNORE_CASE).containsMatchIn(n)))
                }
                tracks = list
                try {
                    val pl = JSONArray(api.get("/api/playlist"))
                    val order = ArrayList<String>()
                    for (i in 0 until pl.length()) {
                        val o = pl.getJSONObject(i)
                        if (o.optString("k") == "srv") order.add(o.getString("ref"))
                    }
                    playlistOrder = order
                } catch (e: Exception) {}
                try {
                    val lo = JSONObject(api.get("/api/loud"))
                    val m = HashMap<String, DoubleArray>()
                    val it = lo.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        val e = lo.optJSONObject(k) ?: continue
                        m[k] = doubleArrayOf(e.optDouble("i", -14.0), e.optDouble("tp", -1.0))
                    }
                    loud = m
                } catch (e: Exception) {}
                try {
                    settings = JSONObject(api.get("/api/settings"))
                    tgt = settings.optDouble("tg", -12.0)
                    normOn = settings.optBoolean("norm", true)
                    dspOn = settings.optBoolean("don", true)
                    shuffle = settings.optBoolean("shuf", false)
                    repeatAll = settings.optBoolean("rp", true)
                    vol = settings.optInt("vl", 100)
                } catch (e: Exception) {}
                runOnUiThread {
                    setOn(btnShuf, shuffle); setOn(btnRep, repeatAll)
                    applyVol(); pushDsp(); refreshList(); refreshSound()
                    status("Server OK · " + tracks.size + " faili")
                }
            } catch (e: Exception) {
                status("Server ei vasta (" + (e.message ?: "?") + ") – vajuta Heli → Server / PIN")
            }
        }
    }

    private fun applyVol() { if (started) player.volume = Math.max(0, Math.min(100, vol)) / 100f }

    private fun normDb(t: Track?): Double {
        if (t == null || !normOn) return 0.0
        val e = loud[t.name] ?: return 0.0
        val maxg = settings.optJSONObject("dsp")?.optDouble("maxg", 9.0) ?: 9.0
        val g = tgt - e[0]
        val cap = (-1.0 - e[1]) + 5.0
        return Math.max(-20.0, Math.min(Math.min(g, cap), maxg))
    }

    private fun pushDsp() {
        proc.update(DspCfg.from(settings.optJSONObject("dsp"), dspOn, normDb(current)))
    }

    private fun saveSettings() {
        val o = JSONObject(settings.toString())
        try {
            o.put("tg", tgt); o.put("norm", normOn); o.put("don", dspOn)
            o.put("shuf", shuffle); o.put("rp", repeatAll); o.put("vl", vol)
            o.put("t", System.currentTimeMillis())
            if (!o.has("dsp")) return
        } catch (e: Exception) { return }
        settings = o
        bg.execute { try { api.put("/api/settings", o.toString()) } catch (e: Exception) { status("Seadete salvestus ebaõnnestus") } }
    }

    private fun playAt(i: Int) {
        if (i < 0 || i >= queue.size) return
        qi = i
        val t = queue[i]
        current = t
        errCount = 0
        load(t, 0)
        refreshNow()
        if (mode in 1..3) refreshList()
        tvTitle.text = t.title
        preloadNext()
    }

    private fun load(t: Track, pos: Long) {
        pushDsp()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(api.musicUrl(t.name))))
        player.prepare()
        if (pos > 0) player.seekTo(pos)
        player.playWhenReady = true
        status("Mängib: " + t.title + (if (normOn && loud[t.name] != null) " · " + String.format("%+.1f", normDb(t)) + " dB" else ""))
    }

    private fun nextIndex(): Int {
        if (queue.isEmpty()) return -1
        if (shuffle && queue.size > 1) {
            if (planned in queue.indices && planned != qi) return planned
            var n: Int
            do { n = (Math.random() * queue.size).toInt() } while (n == qi)
            planned = n
            return n
        }
        var n = qi + 1
        if (n >= queue.size) { if (!repeatAll) return -1; n = 0 }
        return n
    }

    private fun next(auto: Boolean) {
        val n = nextIndex()
        planned = -1
        if (n < 0) { if (auto) player.pause(); return }
        playAt(n)
    }

    private fun prev() {
        if (!started) return
        if (player.currentPosition > 4000) { player.seekTo(0); return }
        if (queue.isEmpty()) return
        playAt(if (qi > 0) qi - 1 else queue.size - 1)
    }

    private fun togglePlay() {
        if (!started) return
        if (current == null) { if (shown.isNotEmpty()) { queue = shown; playAt(0) }; return }
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Slow-connection helper: copy the next song into the on-device cache while this one plays. */
    private fun preloadNext() {
        val n = nextIndex()
        if (n < 0) return
        val t = queue[n]
        if (t.video || prefetched.contains(t.name)) return
        prefetched.add(t.name)
        pre.execute {
            try {
                val ds = cacheFactory.createDataSource()
                CacheWriter(ds, DataSpec(Uri.parse(api.musicUrl(t.name))), null, null).cache()
            } catch (e: Throwable) { prefetched.remove(t.name) }
        }
    }

    // ---------- hardware / steering wheel keys ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { next(false); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { prev(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (started && !player.isPlaying) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (started && player.isPlaying) player.pause(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        if (started) { player.release(); cache.release() }
    }
}
