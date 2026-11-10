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
    private lateinit var btnRep: Button
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
    private var repMode = 1 // 0 off, 1 whole list, 2 current song
    private var settingsLoaded = false
    private var lastLoad = 0L
    private var endHandled = false
    private var dspOn = true
    private var normOn = true
    private var tgt = -12.0
    private var vol = 100
    private var errCount = 0
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

    private fun wide(b: Button): Button {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        return b
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
        buildSoundPane(dl)
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
        btnRep = mkBtn("Kordus: kõik", 2.2f) { repMode = (repMode + 1) % 3; applyRepeat(); saveSettings() }
        tr.addView(btnRep)
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

    private fun refreshSound() { try { for (r in refreshers) r() } catch (e: Exception) {} }

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
                if (state == Player.STATE_ENDED && !endHandled) { endHandled = true; next(true) }
                if (state == Player.STATE_READY) { errCount = 0; endHandled = false }
                updatePlayIcon()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayIcon() }
            override fun onPlayerError(error: PlaybackException) { onError(error) }
        })
        applyRepeat()
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
                    repMode = settings.optInt("rpm", if (settings.optBoolean("rp", true)) 1 else 0)
                    settingsLoaded = true
                    vol = settings.optInt("vl", 100)
                } catch (e: Exception) {}
                runOnUiThread {
                    applyRepeat(); applyVol(); pushDsp(); refreshList(); refreshSound()
                    lastLoad = System.currentTimeMillis()
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
        if (!settingsLoaded) return
        val o = JSONObject(settings.toString())
        try {
            o.put("tg", tgt); o.put("norm", normOn); o.put("don", dspOn)
            o.put("rp", repMode != 0); o.put("rpm", repMode); o.put("vl", vol)
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
        endHandled = false
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
        var n = qi + 1
        if (n >= queue.size) { if (repMode == 0) return -1; n = 0 }
        return n
    }

    private fun next(auto: Boolean) {
        val n = nextIndex()
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
    // ---------- sound tab ----------
    private val refreshers = ArrayList<() -> Unit>()
    private var selBand = 0
    private var saveMode = false
    private var lastPush = 0L

    private fun defEq(): JSONArray {
        val a = JSONArray()
        for (i in DspCfg.EQF.indices) {
            val o = JSONObject()
            o.put("f", DspCfg.EQF[i]); o.put("g", 0.0)
            o.put("q", if (i == 0 || i == DspCfg.EQF.size - 1) 0.7 else 1.2)
            a.put(o)
        }
        return a
    }

    private fun defDsp(): JSONObject {
        val d = JSONObject()
        d.put("int", 100); d.put("pre", 0); d.put("wid", 100); d.put("sat", 0); d.put("hp", 24); d.put("lp", 20000)
        d.put("sub", 0); d.put("enh", 0); d.put("ecf", 110); d.put("eq", defEq())
        d.put("cm", 0); d.put("thr", -24); d.put("rat", 3); d.put("att", 20); d.put("rel", 220); d.put("knee", 12)
        d.put("mk", 0); d.put("mka", true); d.put("lc", -1); d.put("lr", 120); d.put("maxg", 9)
        return d
    }

    private fun dsp(): JSONObject {
        var d = settings.optJSONObject("dsp")
        if (d == null) { d = defDsp(); settings.put("dsp", d) }
        val e = d.optJSONArray("eq")
        if (e == null || e.length() != DspCfg.EQF.size) d.put("eq", defEq())
        return d
    }

    private fun band(): JSONObject = dsp().getJSONArray("eq").getJSONObject(selBand)

    /** name, gains for the 16 EQ bands (or null), extra keys */
    private class Pre(val name: String, val eqg: IntArray?, val kv: Array<Pair<String, Double>>, val eq0: DoubleArray? = null)

    private val PRESETS = listOf(
        Pre("Puhas", null, arrayOf()),
        Pre("Autos", intArrayOf(4, 4, 3, 1, 0, -1, -1, 0, 1, 1, 2, 2, 2, 2, 1, 1),
            arrayOf("cm" to 1.0, "thr" to -22.0, "rat" to 2.5, "sub" to 2.0, "enh" to 20.0, "pre" to -1.0, "hp" to 24.0)),
        Pre("Täis bass", intArrayOf(8, 8, 5, 2, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            arrayOf("sub" to 6.0, "enh" to 45.0, "ecf" to 90.0, "cm" to 2.0, "thr" to -24.0, "sat" to 1.0, "pre" to -3.0, "hp" to 20.0)),
        Pre("Ainult bass", intArrayOf(12, 12, 9, 4, 0, -3, -5, -6, -6, -6, -6, -6, -6, -6, -6, -6),
            arrayOf("sub" to 9.0, "enh" to 60.0, "ecf" to 100.0, "lp" to 7000.0, "cm" to 1.0, "thr" to -26.0, "rat" to 3.0, "sat" to 1.0, "pre" to -5.0, "hp" to 20.0)),
        Pre("Vokaal", intArrayOf(-4, -4, -3, -1, 0, 0, 1, 2, 3, 3, 3, 2, 1, 0, 0, 0),
            arrayOf("hp" to 60.0, "cm" to 1.0, "thr" to -24.0, "rat" to 2.5, "sub" to 0.0, "enh" to 0.0)),
        Pre("35 Hz MAX", null, arrayOf("hp" to 20.0, "pre" to -4.0), doubleArrayOf(35.0, 15.0, 0.3)),
        Pre("Sub 45 MAX", null, arrayOf("sub" to 10.0, "enh" to 30.0, "pre" to -3.0, "hp" to 20.0)),
        Pre("Vaikne", intArrayOf(2, 2, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1),
            arrayOf("cm" to 1.0, "thr" to -32.0, "rat" to 4.0, "pre" to 0.0))
    )

    private fun applyPre(p: Pre) {
        val d = defDsp()
        for (kv in p.kv) {
            if (kv.first == "cm" || kv.first == "sat") d.put(kv.first, kv.second.toInt()) else d.put(kv.first, kv.second)
        }
        val e = d.getJSONArray("eq")
        if (p.eqg != null) for (i in p.eqg.indices) e.getJSONObject(i).put("g", p.eqg[i].toDouble())
        if (p.eq0 != null) {
            val b = e.getJSONObject(0)
            b.put("f", p.eq0[0]); b.put("g", p.eq0[1]); b.put("q", p.eq0[2])
        }
        settings.put("dsp", d)
        dspChanged(true)
    }

    private fun dspChanged(save: Boolean) {
        pushDsp()
        refreshSound()
        if (save) saveSettings()
    }

    private fun mkRow(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun mkHead(text: String): TextView {
        val t = mkText(text, 16f)
        t.setTextColor(Color.parseColor("#FFC857"))
        t.setPadding(0, dp(10), 0, dp(2))
        return t
    }

    /** Plain SeekBar slider. value = min + progress/div. Live push is throttled, save happens on release. */
    private fun slider(par: LinearLayout, label: String, min: Double, max: Double, div: Int,
                       get: () -> Double, set: (Double) -> Unit, ftxt: (Double) -> String) {
        val tv = mkText("", 14f)
        val sb = SeekBar(this)
        sb.max = Math.round((max - min) * div).toInt()
        var busy = false
        fun show(v: Double) { tv.text = label + ": " + ftxt(v) }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || busy) return
                val v = min + p.toDouble() / div
                set(v); show(v)
                val now = System.currentTimeMillis()
                if (now - lastPush > 120) { lastPush = now; pushDsp() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { pushDsp(); saveSettings() }
        })
        refreshers.add {
            busy = true
            val v = get()
            sb.progress = Math.round((Math.max(min, Math.min(max, v)) - min) * div).toInt()
            show(v)
            busy = false
        }
        par.addView(tv)
        par.addView(sb)
    }

    private fun num(k: String, def: Double): Double = dsp().optDouble(k, def)
    private fun sl(par: LinearLayout, label: String, key: String, min: Double, max: Double, div: Int, def: Double, unit: String) {
        slider(par, label, min, max, div, { num(key, def) }, { v -> dsp().put(key, v) }, { v ->
            (if (div > 1) String.format("%.1f", v) else v.toInt().toString()) + unit
        })
    }

    private fun resetBtn(par: LinearLayout, keys: Array<String>) {
        par.addView(wide(mkBtn("Nulli see rühm", 1f) {
            val d = defDsp()
            for (k in keys) dsp().put(k, d.get(k))
            dspChanged(true)
        }))
    }

    private fun userSlots(): JSONArray {
        val a = settings.optJSONArray("pre")
        val r = JSONArray()
        for (i in 0 until 4) r.put(if (a != null && i < a.length() && !a.isNull(i)) a.get(i) else JSONObject.NULL)
        return r
    }

    private fun buildSoundPane(dl: LinearLayout) {
        dl.setPadding(dp(4), dp(4), dp(4), dp(12))
        val bDsp = mkBtn("DSP", 1f) { dspOn = !dspOn; dspChanged(false); saveSettings() }
        val bNorm = mkBtn("Ühtlustus", 1f) { normOn = !normOn; dspChanged(false); saveSettings() }
        val r0 = mkRow(); r0.addView(bDsp); r0.addView(bNorm); dl.addView(r0)
        val tgtTv = mkText("", 14f)
        val r1 = mkRow()
        r1.addView(mkBtn("−", 1f) { tgt = Math.max(-23.0, tgt - 1); dspChanged(false); saveSettings() })
        r1.addView(tgtTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        r1.addView(mkBtn("+", 1f) { tgt = Math.min(-6.0, tgt + 1); dspChanged(false); saveSettings() })
        dl.addView(r1)
        slider(dl, "Helitugevus", 0.0, 100.0, 1, { vol.toDouble() }, { v -> vol = v.toInt(); applyVol() }, { v -> v.toInt().toString() + "%" })
        val info = mkText("", 12f)
        info.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(info)
        refreshers.add {
            setOn(bDsp, dspOn); setOn(bNorm, normOn)
            tgtTv.text = "Ühtlustuse siht: " + tgt.toInt() + " LUFS"
            val n = normDb(current)
            info.text = "DSP " + (if (dspOn) "sees" else "väljas") + " · ühtlustus " + String.format("%+.1f", n) + " dB"
        }

        dl.addView(mkHead("Eelseaded"))
        for (o in intArrayOf(0, 4)) {
            val r = mkRow()
            for (p in PRESETS.subList(o, o + 4)) r.addView(mkBtn(p.name, 1f) { applyPre(p); status("Eelseade: " + p.name) })
            dl.addView(r)
        }
        dl.addView(mkHead("Minu eelseaded"))
        val ur = mkRow()
        val ubs = ArrayList<Button>()
        for (i in 0 until 4) {
            val b = mkBtn("Oma " + (i + 1), 1f) {
                val u = userSlots()
                if (saveMode) {
                    u.put(i, JSONObject(dsp().toString()))
                    settings.put("pre", u)
                    saveMode = false
                    saveSettings(); refreshSound()
                    status("Salvestatud: Oma " + (i + 1))
                } else {
                    val o = u.optJSONObject(i)
                    if (o == null) status("Oma " + (i + 1) + " on tühi – vajuta enne Salvesta")
                    else { settings.put("dsp", JSONObject(o.toString())); dspChanged(true); status("Laetud: Oma " + (i + 1)) }
                }
            }
            ubs.add(b); ur.addView(b)
        }
        dl.addView(ur)
        val bSave = wide(mkBtn("Salvesta praegune → vali Oma 1-4", 1f) { saveMode = !saveMode; refreshSound() })
        dl.addView(bSave)
        refreshers.add {
            setOn(bSave, saveMode)
            val u = userSlots()
            for (i in 0 until 4) ubs[i].text = "Oma " + (i + 1) + (if (u.isNull(i)) "" else " ●")
        }

        dl.addView(mkHead("Bass"))
        sl(dl, "Sub bass 45 Hz", "sub", 0.0, 12.0, 1, 0.0, " dB")
        sl(dl, "Harmoonikud", "enh", 0.0, 100.0, 1, 0.0, "%")
        sl(dl, "Harmoonikute piir", "ecf", 60.0, 200.0, 1, 110.0, " Hz")
        sl(dl, "Madalpiirang (HP)", "hp", 10.0, 80.0, 1, 24.0, " Hz")
        sl(dl, "Kõrgpiirang (LP)", "lp", 3000.0, 20000.0, 1, 20000.0, " Hz")
        resetBtn(dl, arrayOf("sub", "enh", "ecf", "hp", "lp"))

        dl.addView(mkHead("Üldine"))
        sl(dl, "Eelvõimendus", "pre", -12.0, 12.0, 1, 0.0, " dB")
        sl(dl, "Stereo laius", "wid", 0.0, 200.0, 1, 100.0, "%")
        sl(dl, "Intensiivsus", "int", 0.0, 100.0, 1, 100.0, "%")
        sl(dl, "Max ühtlustuse tõus", "maxg", 0.0, 15.0, 1, 9.0, " dB")
        val rs = mkRow()
        val bSat = ArrayList<Button>()
        val sn = arrayOf("Küllastus: ei", "Pehme", "Tugev")
        for (i in 0 until 3) { val b = mkBtn(sn[i], 1f) { dsp().put("sat", i); dspChanged(true) }; bSat.add(b); rs.addView(b) }
        dl.addView(rs)
        refreshers.add { val s = dsp().optInt("sat", 0); for (i in 0 until 3) setOn(bSat[i], s == i) }
        resetBtn(dl, arrayOf("pre", "wid", "int", "sat", "maxg"))

        dl.addView(mkHead("Equalizer (16 riba)"))
        val bandBtns = ArrayList<Button>()
        for (o in intArrayOf(0, 8)) {
            val r = mkRow()
            for (i in o until o + 8) {
                val b = mkBtn("", 1f) { selBand = i; refreshSound() }
                b.textSize = 11f
                bandBtns.add(b); r.addView(b)
            }
            dl.addView(r)
        }
        refreshers.add {
            val e = dsp().getJSONArray("eq")
            for (i in 0 until 16) {
                val o = e.getJSONObject(i)
                val f = o.optDouble("f", 0.0)
                bandBtns[i].text = (if (f >= 1000) String.format("%.1fk", f / 1000) else f.toInt().toString()) + "\n" + String.format("%+.0f", o.optDouble("g", 0.0))
                setOn(bandBtns[i], i == selBand)
            }
        }
        slider(dl, "Riba sagedus", 0.0, 1000.0, 1,
            { Math.round(Math.log(band().optDouble("f", 100.0) / 20.0) / Math.log(1000.0) * 1000.0).toDouble() },
            { v -> band().put("f", Math.round(20.0 * Math.pow(1000.0, v / 1000.0))) },
            { v -> Math.round(20.0 * Math.pow(1000.0, v / 1000.0)).toString() + " Hz" })
        slider(dl, "Riba võimendus", -15.0, 15.0, 2, { band().optDouble("g", 0.0) }, { v -> band().put("g", v) }, { v -> String.format("%+.1f dB", v) })
        slider(dl, "Riba Q (laius)", 0.3, 6.0, 10, { band().optDouble("q", 1.0) }, { v -> band().put("q", Math.round(v * 10) / 10.0) }, { v -> String.format("%.1f", v) })
        val re = mkRow()
        re.addView(mkBtn("Nulli riba", 1f) {
            band().put("g", 0.0); band().put("f", DspCfg.EQF[selBand])
            band().put("q", if (selBand == 0 || selBand == 15) 0.7 else 1.2); dspChanged(true)
        })
        re.addView(mkBtn("Nulli kõik ribad", 1f) { dsp().put("eq", defEq()); dspChanged(true) })
        dl.addView(re)

        dl.addView(mkHead("Kompressor"))
        val rc = mkRow()
        val cb = ArrayList<Button>()
        val cn = arrayOf("Väljas", "Üks riba", "Tugev")
        for (i in 0 until 3) { val b = mkBtn(cn[i], 1f) { dsp().put("cm", i); dspChanged(true) }; cb.add(b); rc.addView(b) }
        dl.addView(rc)
        refreshers.add { val s = dsp().optInt("cm", 0); for (i in 0 until 3) setOn(cb[i], s == i) }
        sl(dl, "Lävi", "thr", -50.0, 0.0, 1, -24.0, " dB")
        sl(dl, "Suhe", "rat", 1.0, 10.0, 2, 3.0, ":1")
        sl(dl, "Rünnak", "att", 1.0, 100.0, 1, 20.0, " ms")
        sl(dl, "Vabanemine", "rel", 20.0, 1000.0, 1, 220.0, " ms")
        resetBtn(dl, arrayOf("cm", "thr", "rat", "att", "rel", "knee", "mk", "mka"))

        dl.addView(mkHead("Limiter"))
        sl(dl, "Lagi", "lc", -6.0, 0.0, 2, -1.0, " dB")
        sl(dl, "Vabanemine", "lr", 20.0, 500.0, 1, 120.0, " ms")
        resetBtn(dl, arrayOf("lc", "lr"))

        dl.addView(mkHead("Muu"))
        dl.addView(wide(mkBtn("Võta seaded serverist", 1f) { loadAll() }))
        dl.addView(wide(mkBtn("Server / PIN", 1f) { askServer() }))
        dl.addView(wide(mkBtn("Tühjenda vahemälu", 1f) { clearCache() }))
        dl.addView(wide(mkBtn("Uuenda rakendust (laeb mb.apk)", 1f) {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(api.base.trimEnd('/') + "/mb.apk"))) }
            catch (e: Exception) { status("Ei saa linki avada: " + api.base.trimEnd('/') + "/mb.apk") }
        }))
        val ver = mkText("MB Player v" + packageManager.getPackageInfo(packageName, 0).versionName, 12f)
        ver.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(ver)
    }

    private fun applyRepeat() {
        btnRep.text = when (repMode) { 0 -> "Kordus: väljas"; 2 -> "Kordus: lugu"; else -> "Kordus: kõik" }
        setOn(btnRep, repMode != 0)
        if (started) player.repeatMode = if (repMode == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

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

    override fun onResume() {
        super.onResume()
        if (started && System.currentTimeMillis() - lastLoad > 60000) loadAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(ticker)
        if (started) { player.release(); cache.release() }
    }
}
