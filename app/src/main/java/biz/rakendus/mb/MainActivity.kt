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
import android.widget.ImageView
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
    private var FG = Color.parseColor("#FFA63D")
    private val BTN = Color.parseColor("#1B2733")
    private var BTN_ON = Color.parseColor("#B35A00")
    private val THEME_NAMES = arrayOf("Oranž", "Tsüaan", "Punane", "Roheline", "Valge")
    private val THEME_FG = intArrayOf(Color.parseColor("#FFA63D"), Color.parseColor("#7FE9FF"), Color.parseColor("#FF6B6B"), Color.parseColor("#7DFFA0"), Color.parseColor("#FFFFFF"))
    private val THEME_ON = intArrayOf(Color.parseColor("#B35A00"), Color.parseColor("#0F8C99"), Color.parseColor("#A12828"), Color.parseColor("#1E8F4A"), Color.parseColor("#555F6B"))

    private fun recolor(v: View, oldFg: Int, newFg: Int, oldOn: Int, newOn: Int) {
        if (v is TextView && v.currentTextColor == oldFg) v.setTextColor(newFg)
        val bgd = v.background
        if (bgd is android.graphics.drawable.ColorDrawable && bgd.color == oldOn) v.setBackgroundColor(newOn)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i), oldFg, newFg, oldOn, newOn)
    }

    private fun applyTheme(i: Int) {
        val oF = FG; val oO = BTN_ON
        FG = THEME_FG[i]; BTN_ON = THEME_ON[i]
        prefs.edit().putInt("theme", i).apply()
        recolor(rootView, oF, FG, oO, BTN_ON)
        refreshList(); refreshSound()
    }

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
    private lateinit var rootView: LinearLayout
    private lateinit var menuRow: LinearLayout
    private lateinit var tvNow: TextView
    private lateinit var searchBox: EditText
    private var query = ""
    private var lastListKey = ""
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(120)
    private val thumbMissing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    private val thumbPool = Executors.newFixedThreadPool(2)

    // data
    private var tracks: List<Track> = emptyList()
    private var playlistOrder: List<String> = emptyList()
    private var playlistRaw: JSONArray = JSONArray()
    private val favs: MutableSet<String> = HashSet()
    private var selMode = false
    private val selected = HashSet<String>()
    private var dupOnly = false
    private var resumed = false
    private var sleepEnd = 0L
    private var sleepMin = 0
    private var tickN = 0
    private val netPool = Executors.newFixedThreadPool(2)
    private val navMap = HashMap<Int, Button>()
    private lateinit var paneYt: LinearLayout
    private lateinit var paneSet: ScrollView
    private lateinit var panePro: ScrollView
    private lateinit var selBar: LinearLayout
    private lateinit var btnSel: Button
    private lateinit var btnSelAll: Button
    private lateinit var btnSelDel: Button
    private lateinit var btnDup: Button
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
    private var tgt = -9.0
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
        val th = Math.max(0, Math.min(4, prefs.getInt("theme", 0)))
        FG = THEME_FG[th]; BTN_ON = THEME_ON[th]
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

    private fun applyMargins() {
        val side = prefs.getInt("mgs", 40)
        val bot = prefs.getInt("mgb", 56)
        val top = prefs.getInt("mgt", 20)
        rootView.setPadding(dp(side), dp(top), dp(side), dp(bot))
    }

    /** Shrinks the text of a button until it fits inside the button box (called on every layout). */
    private fun fitText(b: TextView, maxSp: Float, minSp: Float) {
        b.addOnLayoutChangeListener { v, l, t, r, bt, _, _, _, _ ->
            val tv = v as TextView
            val h = (bt - t) - tv.paddingTop - tv.paddingBottom
            val w = (r - l) - tv.paddingLeft - tv.paddingRight
            if (h > 0 && w > 0) {
                val d = resources.displayMetrics.scaledDensity
                val paint = android.text.TextPaint(tv.paint)
                val lines = tv.text.toString().split("\n")
                val cap: Float = if (maxSp > 0f) maxSp else ((tv.tag as? Float) ?: (tv.textSize / d))
                if (maxSp <= 0f) tv.tag = cap
                var sp = cap
                while (sp > minSp) {
                    paint.textSize = sp * d
                    val fm = paint.fontMetrics
                    var mw = 0f
                    for (ln in lines) mw = Math.max(mw, paint.measureText(ln))
                    if ((fm.descent - fm.ascent) * lines.size <= h && mw <= w) break
                    sp -= 1f
                }
                if (Math.abs(tv.textSize / d - sp) > 0.5f) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
            }
        }
    }

    private fun mkBig(text: String, weight: Float, size: Float, hDp: Int, click: () -> Unit): Button {
        val b = mkBtn(text, weight, click)
        b.textSize = size
        b.setPadding(dp(6), dp(2), dp(6), dp(2))
        b.minHeight = dp(hDp)
        b.minimumHeight = dp(hDp)
        fitText(b, size, 11f)
        return b
    }

    private fun setOn(b: Button, on: Boolean) { b.setBackgroundColor(if (on) BTN_ON else BTN) }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f))
        (right.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
        root.setBackgroundColor(BG)
        rootView = root
        applyMargins()

        tvTitle = mkText("MB PLAYER", 24f)
        tvTitle.setSingleLine(true)
        tvTitle.setOnLongClickListener { askServer(); true }
        val hdr = LinearLayout(this)
        hdr.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.mipmap.ic_launcher)
        hdr.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))
        val hcol = LinearLayout(this)
        hcol.orientation = LinearLayout.VERTICAL
        hcol.setPadding(dp(10), 0, 0, 0)
        hcol.addView(tvTitle)
        val tvBrand = mkText("W211 · Andres Aareleid", 13f)
        tvBrand.setTextColor(Color.parseColor("#8899AA"))
        hcol.addView(tvBrand)
        hdr.addView(hcol)
        left.addView(hdr)
        tvStatus = mkText("", 13f)
        tvStatus.setTextColor(Color.parseColor("#8899AA"))
        tvStatus.textSize = 13f
        left.addView(tvStatus)
        tvNow = mkText("", 20f)
        tvNow.maxLines = 4
        tvNow.setPadding(0, dp(8), 0, dp(8))
        left.addView(tvNow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val content = FrameLayout(this)
        right.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // list pane
        paneList = LinearLayout(this)
        paneList.orientation = LinearLayout.VERTICAL
        chipsScroll = HorizontalScrollView(this)
        chipsBox = LinearLayout(this)
        chipsBox.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(chipsBox)
        paneList.addView(chipsScroll)
        selBar = LinearLayout(this)
        btnSel = mkBig("Vali", 1f, 17f, 52) { selMode = !selMode; if (!selMode) selected.clear(); refreshList() }
        btnSelAll = mkBig("Kõik", 1f, 17f, 52) { for (t in shown) selected.add(t.name); refreshList() }
        btnSelDel = mkBig("Kustuta (0)", 1.6f, 17f, 52) { deleteSelected() }
        btnDup = mkBig("Dubl. ✕", 1f, 17f, 52) { dupOnly = false; selMode = false; selected.clear(); refreshList() }
        selBar.addView(btnSel); selBar.addView(btnSelAll); selBar.addView(btnSelDel); selBar.addView(btnDup)
        paneList.addView(selBar)
        searchBox = EditText(this)
        searchBox.hint = "Otsi…"
        searchBox.setHintTextColor(Color.parseColor("#556677"))
        searchBox.setTextColor(FG)
        searchBox.textSize = 20f
        searchBox.setSingleLine(true)
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString()?.trim() ?: ""; lastListKey = ""; refreshList() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        paneList.addView(searchBox)
        listView = ListView(this)
        listView.setBackgroundColor(BG)
        listView.divider = null
        listView.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < shown.size) {
                if (selMode) { val nm = shown[pos].name; if (!selected.add(nm)) selected.remove(nm); refreshList() }
                else { queue = shown; playAt(pos) }
            }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (!selMode && pos >= 0 && pos < shown.size) itemMenu(shown[pos])
            true
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

        // settings pane
        paneSet = ScrollView(this)
        val sl2 = LinearLayout(this)
        sl2.orientation = LinearLayout.VERTICAL
        paneSet.addView(sl2)
        buildSettingsPane(sl2)
        content.addView(paneSet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Pro pane (manual DSP controls)
        panePro = ScrollView(this)
        val pl2 = LinearLayout(this)
        pl2.orientation = LinearLayout.VERTICAL
        panePro.addView(pl2)
        buildProPane(pl2)
        content.addView(panePro, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // YouTube pane
        paneYt = LinearLayout(this)
        paneYt.orientation = LinearLayout.VERTICAL
        buildYtPane(paneYt)
        content.addView(paneYt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
        left.addView(sr)

        // menu panel: covers the right side until a tab is chosen
        menuRow = LinearLayout(this)
        menuRow.orientation = LinearLayout.VERTICAL
        menuRow.setBackgroundColor(BG)
        menuRow.visibility = View.GONE
        val items = arrayOf("Laetud" to 1, "Server" to 2, "Playlist" to 3, "Lemmikud" to 7, "YouTube" to 5, "Heli" to 4, "Pro" to 8, "Seaded" to 6, "Video" to 0)
        for ((label, m) in items) {
            val b = mkBtn(label, 1f) { showMode(m); menuRow.visibility = View.GONE }
            val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            mlp.setMargins(dp(3), dp(2), dp(3), dp(2))
            b.layoutParams = mlp
            b.textSize = 26f
            b.setPadding(dp(6), 0, dp(6), 0)
            b.minHeight = 0
            b.minimumHeight = 0
            fitText(b, 26f, 12f)
            navMap[m] = b
            menuRow.addView(b)
        }
        content.addView(menuRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // transport: big buttons on the left side
        val tr = LinearLayout(this)
        tr.addView(mkBig("◀◀", 1f, 30f, 96) { prev() })
        btnPlay = mkBig("▶", 1.4f, 36f, 96) { togglePlay() }
        tr.addView(btnPlay)
        tr.addView(mkBig("▶▶", 1f, 30f, 96) { next(false) })
        left.addView(tr)
        val tr2 = LinearLayout(this)
        btnRep = mkBig("KORDA kõik", 1f, 18f, 80) { repMode = (repMode + 1) % 3; applyRepeat(); saveSettings() }
        tr2.addView(btnRep)
        tr2.addView(mkBig("☰ MENÜÜ", 1f, 18f, 80) { menuRow.visibility = if (menuRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        left.addView(tr2)
        setContentView(root)
        showMode(1)
    }

    private var shown: List<Track> = emptyList()

    private fun isList(m: Int): Boolean = m == 1 || m == 2 || m == 3 || m == 7

    private fun showMode(m: Int) {
        if (m != mode) {
            lastListKey = ""
            selMode = false
            selected.clear()
            if (m != 1 && m != 2) dupOnly = false
        }
        mode = m
        for ((k, b) in navMap) setOn(b, k == m)
        panePlay.visibility = if (m == 0) View.VISIBLE else View.GONE
        paneDsp.visibility = if (m == 4) View.VISIBLE else View.GONE
        paneYt.visibility = if (m == 5) View.VISIBLE else View.GONE
        paneSet.visibility = if (m == 6) View.VISIBLE else View.GONE
        panePro.visibility = if (m == 8) View.VISIBLE else View.GONE
        paneList.visibility = if (isList(m)) View.VISIBLE else View.GONE
        if (isList(m)) refreshList()
        if (m == 4 || m == 6 || m == 8) refreshSound()
        if (m == 0) refreshNow()
    }

    private fun dupKey(t: Track): String = t.title.lowercase().replace(Regex("-\\d+$"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun updateSelBar() {
        setOn(btnSel, selMode)
        btnSelAll.visibility = if (selMode) View.VISIBLE else View.GONE
        btnSelDel.visibility = if (selMode) View.VISIBLE else View.GONE
        btnSelDel.text = "Kustuta (" + selected.size + ")"
        btnDup.visibility = if (dupOnly) View.VISIBLE else View.GONE
        selBar.visibility = if (mode == 3 || mode == 7) View.GONE else View.VISIBLE
    }

    private fun refreshList() {
        if (!started) return
        val dupKeys = HashMap<String, Int>()
        if (dupOnly) for (t in tracks) { val k = dupKey(t); dupKeys[k] = (dupKeys[k] ?: 0) + 1 }
        val base: List<Track> = when (mode) {
            1 -> tracks.filter { it.dl && (dlFilter == null || it.pl == dlFilter) && (!dupOnly || (dupKeys[dupKey(it)] ?: 0) > 1) }.let { l -> if (dupOnly) l.sortedBy { dupKey(it) } else l }
            2 -> tracks.filter { !it.dl && (!dupOnly || (dupKeys[dupKey(it)] ?: 0) > 1) }.let { l -> if (dupOnly) l.sortedBy { dupKey(it) } else l }
            7 -> tracks.filter { favs.contains(it.name) }
            else -> {
                val byName = HashMap<String, Track>()
                for (t in tracks) byName[t.name] = t
                playlistOrder.mapNotNull { byName[it] }.distinctBy { it.name }
            }
        }
        val base2: List<Track> = if (query.isEmpty()) base else base.filter { it.title.contains(query, true) }
        shown = base2
        val key = mode.toString() + "|" + dlFilter + "|" + query
        val keepPos = if (key == lastListKey) listView.firstVisiblePosition else 0
        val keepTop = if (key == lastListKey && listView.childCount > 0) listView.getChildAt(0).top else 0
        lastListKey = key
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
        val ad = object : ArrayAdapter<Track>(this, 0, base2) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val iv: ImageView
                val tv: TextView
                if (convertView is LinearLayout) {
                    row = convertView
                    iv = row.getChildAt(0) as ImageView
                    tv = row.getChildAt(1) as TextView
                } else {
                    row = LinearLayout(this@MainActivity)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(dp(8), dp(8), dp(8), dp(8))
                    iv = ImageView(this@MainActivity)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    row.addView(iv, LinearLayout.LayoutParams(dp(96), dp(54)))
                    tv = TextView(this@MainActivity)
                    tv.setTextColor(FG)
                    tv.textSize = 22f
                    tv.setPadding(dp(12), 0, 0, 0)
                    row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                val t = getItem(position)!!
                val c = current
                tv.text = (if (selMode) (if (selected.contains(t.name)) "☑ " else "☐ ") else "") + (if (favs.contains(t.name)) "★ " else "") + (if (c != null && c.name == t.name) "▶ " else "") + (if (t.video) "[video] " else "") + t.title
                row.setBackgroundColor(if (selMode && selected.contains(t.name)) BTN_ON else Color.TRANSPARENT)
                if (t.dl) {
                    iv.visibility = View.VISIBLE
                    iv.tag = t.name
                    val bm = thumbCache.get(t.name)
                    if (bm != null) iv.setImageBitmap(bm) else { iv.setImageDrawable(null); iv.setBackgroundColor(BTN); loadThumb(t.name, iv) }
                    if (bm != null) iv.setBackgroundColor(BG)
                } else {
                    iv.visibility = View.GONE
                }
                return row
            }
        }
        listView.adapter = ad
        updateSelBar()
        if (keepPos > 0) listView.setSelectionFromTop(keepPos, keepTop)
        if (base2.isEmpty()) tvStatus.text = "Siin pole veel midagi"
    }

    private fun loadThumb(name: String, iv: ImageView) {
        if (thumbMissing.contains(name)) return
        thumbPool.execute {
            try {
                val f = File(File(cacheDir, "thumbs"), Integer.toHexString(name.hashCode()) + ".jpg")
                var data: ByteArray? = null
                if (f.exists()) data = f.readBytes()
                if (data == null) {
                    data = api.bytes("/api/thumb?n=" + Uri.encode(name))
                    if (data != null) { f.parentFile?.mkdirs(); f.writeBytes(data) }
                }
                if (data == null) { thumbMissing.add(name); return@execute }
                val bm = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bm == null) { thumbMissing.add(name); return@execute }
                thumbCache.put(name, bm)
                ui.post { if (iv.tag == name) { iv.setImageBitmap(bm); iv.setBackgroundColor(BG) } }
            } catch (e: Throwable) {}
        }
    }

    private fun bigDialog(title: String, opts: List<Pair<String, () -> Unit>>) {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(12), dp(8), dp(12), dp(8))
        val sv = ScrollView(this)
        sv.addView(l)
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(sv).setNegativeButton("Tühista", null).create()
        for (o in opts) {
            val b = wide(mkBtn(o.first, 1f) { dlg.dismiss(); o.second() }, 64)
            b.textSize = 22f
            l.addView(b)
        }
        dlg.show()
    }

    private fun itemMenu(t: Track) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (playlistOrder.contains(t.name)) opts.add("Eemalda playlistist" to { removeFromPlaylist(t) })
        else opts.add("Lisa playlisti" to { addToPlaylist(t) })
        opts.add((if (favs.contains(t.name)) "☆ Eemalda lemmikutest" else "★ Lemmikuks") to { toggleFav(t) })
        if (mode == 3) {
            opts.add("▲ Üles" to { moveInPlaylist(t, -1) })
            opts.add("▼ Alla" to { moveInPlaylist(t, 1) })
            opts.add("⇈ Esimeseks" to { moveInPlaylist(t, 0) })
        }
        opts.add("Kustuta serverist…" to {
            bigDialog("Kustuta päriselt?\n" + t.title, listOf<Pair<String, () -> Unit>>("JAH, KUSTUTA" to { deleteTrack(t) }))
        })
        bigDialog(t.title, opts)
    }

    private fun savePlaylist(arr: JSONArray) {
        playlistRaw = arr
        val order = ArrayList<String>()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("k") == "srv") order.add(o.getString("ref")) }
        playlistOrder = order
        bg.execute {
            try { api.put("/api/playlist" + (if (arr.length() == 0) "?force=1" else ""), arr.toString()); status("Playlist salvestatud") }
            catch (e: Exception) { status("Playlisti salvestus ebaõnnestus: " + (e.message ?: "?")) }
        }
        refreshList()
    }

    private fun removeFromPlaylist(t: Track) {
        val n = JSONArray()
        for (i in 0 until playlistRaw.length()) {
            val o = playlistRaw.getJSONObject(i)
            if (!(o.optString("k") == "srv" && o.optString("ref") == t.name)) n.put(o)
        }
        savePlaylist(n)
    }

    private fun addToPlaylist(t: Track) {
        val n = JSONArray(playlistRaw.toString())
        val o = JSONObject()
        o.put("k", "srv"); o.put("ref", t.name); o.put("title", t.title)
        n.put(o)
        savePlaylist(n)
    }

    private fun deleteTrack(t: Track) {
        bg.execute {
            try {
                api.post("/api/file/delete?n=" + Uri.encode(t.name))
                runOnUiThread {
                    tracks = tracks.filter { it.name != t.name }
                    val n = JSONArray()
                    for (i in 0 until playlistRaw.length()) {
                        val o = playlistRaw.getJSONObject(i)
                        if (!(o.optString("k") == "srv" && o.optString("ref") == t.name)) n.put(o)
                    }
                    playlistRaw = n
                    playlistOrder = playlistOrder.filter { it != t.name }
                    thumbCache.remove(t.name)
                    refreshList()
                    status("Kustutatud: " + t.title)
                }
            } catch (e: Exception) { status("Kustutamine ebaõnnestus: " + (e.message ?: "?")) }
        }
    }

    private fun rawWithout(names: Set<String>): JSONArray {
        val n = JSONArray()
        for (i in 0 until playlistRaw.length()) {
            val o = playlistRaw.getJSONObject(i)
            if (!(o.optString("k") == "srv" && names.contains(o.optString("ref")))) n.put(o)
        }
        return n
    }

    private fun toggleFav(t: Track) {
        if (!favs.remove(t.name)) favs.add(t.name)
        saveSettings()
        refreshList()
    }

    private fun moveInPlaylist(t: Track, dir: Int) {
        val n = playlistRaw.length()
        val list = ArrayList<JSONObject>()
        for (i in 0 until n) list.add(playlistRaw.getJSONObject(i))
        var idx = -1
        for (i in 0 until n) if (list[i].optString("k") == "srv" && list[i].optString("ref") == t.name) { idx = i; break }
        if (idx < 0) return
        if (dir == 0) {
            val item = list.removeAt(idx)
            list.add(0, item)
        } else {
            var j = idx + dir
            while (j in 0 until n && list[j].optString("k") != "srv") j += dir
            if (j < 0 || j >= n) return
            val tmp = list[idx]; list[idx] = list[j]; list[j] = tmp
        }
        val out = JSONArray()
        for (o in list) out.put(o)
        savePlaylist(out)
    }

    private fun deleteSelected() {
        val names = selected.toList()
        if (names.isEmpty()) { status("Vali enne lood (puuduta ridu)"); return }
        bigDialog("Kustuta " + names.size + " lugu päriselt?", listOf<Pair<String, () -> Unit>>("JAH, KUSTUTA KÕIK" to { runBulkDelete(names) }))
    }

    private fun runBulkDelete(names: List<String>) {
        bg.execute {
            var ok = 0
            for ((i, nm) in names.withIndex()) {
                try { api.post("/api/file/delete?n=" + Uri.encode(nm)); ok++ } catch (e: Exception) {}
                status("Kustutan " + (i + 1) + " / " + names.size)
            }
            runOnUiThread {
                val gone = names.toSet()
                tracks = tracks.filter { !gone.contains(it.name) }
                playlistRaw = rawWithout(gone)
                playlistOrder = playlistOrder.filter { !gone.contains(it) }
                favs.removeAll(gone)
                selected.clear()
                selMode = false
                refreshList()
                saveSettings()
                status("Kustutatud " + ok + " / " + names.size)
            }
        }
    }

    // ---------- YouTube screen ----------
    private class YtRow(val id: String, val title: String, val isPl: Boolean, val n: Int)
    private var ytRows: List<YtRow> = emptyList()
    private lateinit var ytList: ListView
    private lateinit var ytMsg: TextView
    private lateinit var ytUrl: EditText
    private lateinit var ytQ: EditText
    private var ytMusic = true

    private fun mkEdit(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setHintTextColor(Color.parseColor("#556677"))
        e.setTextColor(FG)
        e.textSize = 20f
        e.setSingleLine(true)
        e.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        return e
    }

    private fun askText(title: String, cb: (String) -> Unit) {
        val e = mkEdit("")
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("OK") { _, _ -> val v = e.text.toString().trim(); if (v.isNotEmpty()) cb(v) }
            .setNegativeButton("Tühista", null).show()
    }

    private fun buildYtPane(p: LinearLayout) {
        val info = mkText("Lood laetakse serverisse – siis töötavad DSP ja ühtlustus. YouTube'i otse kuulamine pole rakenduses võimalik.", 13f)
        info.setTextColor(Color.parseColor("#8899AA"))
        p.addView(info)
        ytUrl = mkEdit("Aseta YouTube / SoundCloud link")
        val r1 = mkRow()
        r1.addView(ytUrl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r1.addView(mkBig("Lae", 0.4f, 18f, 52) { ytDownloadUrl() })
        p.addView(r1)
        ytQ = mkEdit("Otsi YouTube'ist")
        ytQ.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        ytQ.setOnEditorActionListener { _, _, _ -> ytSearch(); true }
        val r2 = mkRow()
        r2.addView(ytQ, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r2.addView(mkBig("Otsi", 0.4f, 18f, 52) { ytSearch() })
        p.addView(r2)
        val r3 = mkRow()
        r3.addView(mkBig("Minu playlistid", 1f, 17f, 52) { ytPlaylists() })
        var bm: Button? = null
        bm = mkBig("Otsing: muusika", 1f, 17f, 52) { ytMusic = !ytMusic; bm?.text = if (ytMusic) "Otsing: muusika" else "Otsing: kõik" }
        r3.addView(bm!!)
        p.addView(r3)
        ytMsg = mkText("", 14f)
        p.addView(ytMsg)
        ytList = ListView(this)
        ytList.setBackgroundColor(BG)
        ytList.divider = null
        ytList.setOnItemClickListener { _, _, pos, _ -> if (pos >= 0 && pos < ytRows.size) ytTap(ytRows[pos]) }
        p.addView(ytList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun ytShow(rows: List<YtRow>) {
        ytRows = rows
        ytList.adapter = object : ArrayAdapter<YtRow>(this, 0, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = if (convertView is TextView) convertView else TextView(this@MainActivity)
                val r = getItem(position)!!
                tv.setTextColor(FG)
                tv.textSize = 22f
                tv.setPadding(dp(12), dp(16), dp(12), dp(16))
                tv.text = (if (r.isPl) "▤ " else "") + r.title + (if (r.isPl && r.n > 0) "  (" + r.n + ")" else "")
                return tv
            }
        }
    }

    private fun ytFail(e: Exception) { runOnUiThread { ytMsg.text = "Viga: " + (e.message ?: "?") } }

    private fun ytSearch() {
        val q = ytQ.text.toString().trim()
        if (q.isEmpty()) return
        ytMsg.text = "Otsin…"
        netPool.execute {
            try {
                val arr = JSONArray(api.get("/api/yt/search?music=" + (if (ytMusic) "1" else "0") + "&q=" + Uri.encode(q)))
                val rows = ArrayList<YtRow>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); rows.add(YtRow(o.getString("id"), o.optString("title"), false, 0)) }
                runOnUiThread { ytShow(rows); ytMsg.text = rows.size.toString() + " tulemust – puuduta, et serverisse laadida" }
            } catch (e: Exception) { ytFail(e) }
        }
    }

    private fun ytPlaylists() {
        ytMsg.text = "Laen playlistid…"
        netPool.execute {
            try {
                val arr = JSONArray(api.get("/api/yt/playlists"))
                val rows = ArrayList<YtRow>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); rows.add(YtRow(o.getString("id"), o.optString("title"), true, o.optInt("n", 0))) }
                runOnUiThread { ytShow(rows); ytMsg.text = rows.size.toString() + " playlisti" }
            } catch (e: Exception) { ytFail(e) }
        }
    }

    private fun ytItems(r: YtRow) {
        ytMsg.text = "Laen lugusid…"
        netPool.execute {
            try {
                val arr = JSONArray(api.get("/api/yt/items?pl=" + Uri.encode(r.id)))
                val rows = ArrayList<YtRow>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); rows.add(YtRow(o.getString("id"), o.optString("title"), false, 0)) }
                runOnUiThread { ytShow(rows); ytMsg.text = r.title + ": " + rows.size + " lugu – puuduta, et serverisse laadida" }
            } catch (e: Exception) { ytFail(e) }
        }
    }

    private fun ytTap(r: YtRow) {
        if (r.isPl) {
            val opts = ArrayList<Pair<String, () -> Unit>>()
            opts.add("Lae kogu playlist serverisse" to { chooseList(r.title) { nm -> startImport(listOf("https://www.youtube.com/playlist?list=" + r.id), true, nm) } })
            opts.add("Vaata lugusid" to { ytItems(r) })
            bigDialog(r.title, opts)
        } else {
            chooseList("") { nm -> startImport(listOf("https://www.youtube.com/watch?v=" + r.id), false, nm) }
        }
    }

    private fun ytDownloadUrl() {
        val u = ytUrl.text.toString().trim()
        if (!u.contains("youtu") && !u.contains("soundcloud")) { ytMsg.text = "Aseta YouTube või SoundCloud link"; return }
        val all = u.contains("list=") || u.contains("/playlist") || u.contains("/sets/")
        chooseList("") { nm -> startImport(listOf(u), all, nm) }
    }

    private fun chooseList(suggest: String, cb: (String) -> Unit) {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (suggest.isNotEmpty()) opts.add(("Uus: " + suggest) to { cb(suggest) })
        for (n in tracks.filter { it.dl && it.pl != null }.map { it.pl!! }.distinct().sorted()) opts.add(n to { cb(n) })
        opts.add("Uus nimekiri…" to { askText("Nimekirja nimi") { v -> cb(v) } })
        opts.add("Nimekirjata" to { cb("") })
        bigDialog("Millisesse nimekirja lisada?", opts)
    }

    private fun startImport(urls: List<String>, all: Boolean, name: String) {
        ytMsg.text = "Saadan serverile…"
        netPool.execute {
            try {
                val b = JSONObject()
                b.put("urls", JSONArray(urls)); b.put("all", all); b.put("name", name)
                val r = JSONObject(api.postJson("/api/import", b.toString()))
                runOnUiThread { ytMsg.text = "Server laeb alla – DSP töötab pärast allalaadimist" }
                pollJob(r.getString("id"), 0)
            } catch (e: Exception) { ytFail(e) }
        }
    }

    private fun pollJob(id: String, n: Int) {
        netPool.execute {
            try {
                val j = JSONObject(api.get("/api/job?id=" + Uri.encode(id)))
                val st = j.optString("state")
                val msg = j.optString("msg")
                runOnUiThread { ytMsg.text = msg; status(msg) }
                if ((st == "queued" || st == "running") && n < 1200) ui.postDelayed({ pollJob(id, n + 1) }, 2500)
                else runOnUiThread { loadAll() }
            } catch (e: Exception) { ytFail(e) }
        }
    }

    private fun pollThumbs(n: Int) {
        netPool.execute {
            try {
                val j = JSONObject(api.get("/api/thumbs/status"))
                val busy = j.optBoolean("busy", false)
                status("Pisipildid: " + j.optInt("have") + " / " + j.optInt("total") + (if (busy) " (töös)" else " – valmis"))
                if (busy && n < 2000) ui.postDelayed({ pollThumbs(n + 1) }, 3000)
                else { thumbMissing.clear(); runOnUiThread { refreshList() } }
            } catch (e: Exception) {}
        }
    }

    private fun saveLast() {
        val c = current
        if (!started || c == null) return
        prefs.edit().putString("lastName", c.name).putLong("lastPos", player.currentPosition).apply()
    }

    private fun tryResume() {
        if (resumed || !started) return
        resumed = true
        if (current != null) return
        val nm = prefs.getString("lastName", null) ?: return
        val t = tracks.firstOrNull { it.name == nm } ?: return
        val pos = prefs.getLong("lastPos", 0L)
        val q = tracks.filter { it.dl == t.dl }
        queue = q
        qi = q.indexOfFirst { it.name == t.name }
        current = t
        load(t, if (pos > 3000) pos - 2000 else 0L, prefs.getBoolean("auto", true))
        refreshNow()
        refreshList()
    }

    private fun refreshNow() {
        val t = current
        tvInfo.text = if (t == null) "Video puudub" else if (t.video) t.title else "Praegu mängib helifail – video näidatakse siin"
        tvNow.text = if (t == null) "Vali lugu" else t.title
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
            override fun onIsPlayingChanged(isPlaying: Boolean) { updatePlayIcon(); if (!isPlaying) saveLast() }
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
                tickN++
                if (tickN % 10 == 0 && started && player.isPlaying) saveLast()
                if (sleepEnd > 0L && System.currentTimeMillis() >= sleepEnd) {
                    sleepEnd = 0L; sleepMin = 0
                    if (started) player.pause()
                    status("Uni-taimer: muusika peatatud")
                    refreshSound()
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
                    playlistRaw = pl
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
                    tgt = settings.optDouble("tg", -9.0)
                    normOn = settings.optBoolean("norm", true)
                    dspOn = settings.optBoolean("don", true)
                    repMode = settings.optInt("rpm", if (settings.optBoolean("rp", true)) 1 else 0)
                    readW()
                    favs.clear()
                    val fa = settings.optJSONArray("fav")
                    if (fa != null) for (i in 0 until fa.length()) favs.add(fa.optString(i))
                    settingsLoaded = true
                    vol = settings.optInt("vl", 100)
                } catch (e: Exception) {}
                runOnUiThread {
                    thumbMissing.clear()
                    applyRepeat(); applyVol(); pushDsp(); refreshList(); refreshSound()
                    tryResume()
                    checkUpdate(false)
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
            val fj = JSONArray()
            for (f in favs) fj.put(f)
            o.put("fav", fj)
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
        if (isList(mode)) refreshList()
        preloadNext()
    }

    private fun load(t: Track, pos: Long, play: Boolean = true) {
        pushDsp()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(api.musicUrl(t.name))))
        player.prepare()
        if (pos > 0) player.seekTo(pos)
        player.playWhenReady = play
        status(if (normOn && loud[t.name] != null) "Ühtlustus " + String.format("%+.1f", normDb(t)) + " dB" else "")
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
    private var lastPush = 0L
    private var wMode = -1
    private val wStr = doubleArrayOf(100.0, 100.0, 70.0, 70.0, 70.0)
    private val W_NAMES = arrayOf("45 Hz BASS  (täis + liugur)", "36 Hz BASS  (täis + liugur)", "Optimeeritud 3D", "Optimeeritud Bass / Vokaal", "Optimeeritud HipHop / Bass / Pop")

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

    /**
     * W211 (Harman/Kardon / Logic7) tuned modes. s = strength 0..1.
     * Safety rules used in every mode: subsonic high-pass at 26-28 Hz (the car's small door/dash drivers
     * cannot reproduce lower and just distort), no low-shelf boost (a shelf also lifts everything below
     * the speakers' limit), pre-gain lowered with the bass boost so the amp keeps headroom, limiter at -2 dB.
     */
    private fun w211(m: Int, s: Double): JSONObject {
        val d = defDsp()
        val e = d.getJSONArray("eq")
        fun g(i: Int, db: Double) { e.getJSONObject(i).put("g", db * s) }
        d.put("lc", -2.0); d.put("lr", 150)
        d.put("hp", 28)
        when (m) {
            0 -> { // 45 Hz
                d.put("sub", 11.0 * s); d.put("enh", 30.0 * s); d.put("ecf", 90)
                g(2, 1.5)
                d.put("cm", 1); d.put("thr", -10); d.put("rat", 1.0 + 0.5 * s)
                d.put("pre", -3.5 * s)
            }
            1 -> { // 36 Hz (peaking band, not a shelf)
                d.put("hp", 26)
                val b = e.getJSONObject(1)
                b.put("f", 36.0); b.put("g", 14.0 * s); b.put("q", 1.0)
                g(2, 2.0)
                d.put("enh", 25.0 * s); d.put("ecf", 80)
                d.put("cm", 1); d.put("thr", -10); d.put("rat", 1.0 + 0.5 * s)
                d.put("pre", -4.0 * s)
            }
            2 -> { // 3D: wider stage + air
                d.put("wid", 100.0 + 55.0 * s)
                g(1, 2.0); g(2, 2.0); g(6, -1.0); g(12, 1.5); g(13, 2.0); g(14, 2.0); g(15, 1.5)
                d.put("enh", 10.0 * s); d.put("ecf", 100)
                d.put("pre", -2.5 * s)
            }
            3 -> { // bass + vocal
                g(1, 5.0); g(2, 4.0); g(3, 1.5); g(5, -1.5); g(6, -1.5)
                g(8, 1.5); g(9, 2.5); g(10, 3.0); g(11, 2.0); g(12, 1.0)
                d.put("sub", 4.0 * s); d.put("enh", 15.0 * s); d.put("ecf", 100)
                d.put("cm", 1); d.put("thr", -12); d.put("rat", 1.0 + 0.6 * s)
                d.put("pre", -3.0 * s)
            }
            else -> { // hip-hop / bass / pop
                g(1, 6.0); g(2, 5.0); g(3, 2.0); g(5, -2.0); g(6, -2.0); g(7, -1.0)
                g(11, 1.5); g(12, 2.0); g(13, 3.0); g(14, 3.0); g(15, 2.0)
                d.put("sub", 6.0 * s); d.put("enh", 25.0 * s); d.put("ecf", 100)
                d.put("cm", 1); d.put("thr", -12); d.put("rat", 1.0 + 0.8 * s)
                d.put("sat", if (s > 0.5) 1 else 0)
                d.put("wid", 100.0 + 10.0 * s)
                d.put("pre", -3.5 * s)
            }
        }
        return d
    }

    private fun saveW() {
        val w = JSONObject()
        w.put("m", wMode)
        val a = JSONArray()
        for (v in wStr) a.put(v)
        w.put("s", a)
        settings.put("w", w)
    }

    private fun applyW(save: Boolean) {
        if (wMode < 0) return
        settings.put("dsp", w211(wMode, wStr[wMode] / 100.0))
        saveW()
        pushDsp()
        refreshSound()
        if (save) saveSettings()
    }

    private fun readW() {
        val w = settings.optJSONObject("w") ?: return
        wMode = w.optInt("m", -1)
        val a = w.optJSONArray("s")
        if (a != null) for (i in 0 until Math.min(5, a.length())) wStr[i] = Math.max(0.0, Math.min(100.0, a.optDouble(i, wStr[i])))
    }

    private fun mkRow(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun mkHead(text: String): TextView {
        val t = mkText(text, 16f)
        t.setTextColor(Color.parseColor("#FFC857"))
        t.setPadding(0, dp(12), 0, dp(2))
        return t
    }

    /** Plain SeekBar slider (no animation). Live push is throttled, the server save happens on release. */
    // ---------- In-app updater ----------
    private fun apkUrl(): String = api.base.trimEnd('/') + "/mb.apk"

    private fun checkUpdate(manual: Boolean) {
        bg.execute {
            try {
                val hc = java.net.URL(apkUrl()).openConnection() as java.net.HttpURLConnection
                hc.requestMethod = "HEAD"
                hc.connectTimeout = 12000
                hc.readTimeout = 12000
                val code = hc.responseCode
                val stamp = hc.getHeaderField("Last-Modified") + "|" + hc.contentLength
                hc.disconnect()
                if (code != 200) { if (manual) status("Uuenduse kontroll: HTTP " + code); return@execute }
                val old = prefs.getString("apkStamp", null)
                if (!manual) {
                    if (old == null) { prefs.edit().putString("apkStamp", stamp).apply(); return@execute }
                    if (old == stamp) return@execute
                }
                if (manual) status("Kontrollin uuendust…")
                val dir = java.io.File(cacheDir, "update")
                dir.mkdirs()
                val f = java.io.File(dir, "mb.apk")
                val dc = java.net.URL(apkUrl()).openConnection() as java.net.HttpURLConnection
                dc.connectTimeout = 15000
                dc.readTimeout = 30000
                if (dc.responseCode != 200) { dc.disconnect(); status("Uuenduse allalaadimine: HTTP " + dc.responseCode); return@execute }
                val total = dc.contentLength.toLong()
                var got = 0L
                var lastPct = -1
                dc.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val pct = (got * 100 / total).toInt()
                                if (pct / 10 != lastPct / 10) { lastPct = pct; status("Laen uuendust… " + pct + "%") }
                            }
                        }
                    }
                }
                dc.disconnect()
                val info = packageManager.getPackageArchiveInfo(f.path, 0)
                if (info == null) { status("Uuendusfail ei ole korrektne APK"); return@execute }
                val cur = packageManager.getPackageInfo(packageName, 0)
                prefs.edit().putString("apkStamp", stamp).apply()
                if (info.versionCode > cur.versionCode) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Uus versioon " + info.versionName)
                            .setMessage("Sul on v" + cur.versionName + ". Paigaldan uue versiooni? Muusika peatub ja rakendus taaskäivitub.")
                            .setPositiveButton("Paigalda") { _, _ -> installApk(f) }
                            .setNegativeButton("Hiljem", null)
                            .show()
                    }
                } else if (manual) status("Sul on uusim versioon (v" + cur.versionName + ")")
            } catch (e: Exception) {
                if (manual) status("Uuenduse viga: " + (e.message ?: "tundmatu"))
            }
        }
    }

    private fun installApk(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fp", f)
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) { status("Paigaldus ebaõnnestus: " + (e.message ?: "viga")) }
    }

    private val saveDeb = Runnable { saveSettings() }

    /** [−] value [+] control: exact numbers, no accidental swipes while driving. */
    private fun stepper(par: LinearLayout, label: String, min: Double, max: Double, step: Double,
                        get: () -> Double, set: (Double) -> Unit, ftxt: (Double) -> String, desc: String = "") {
        stepperF(par, label, min, max, { step }, get, set, ftxt, desc)
    }

    private fun stepperF(par: LinearLayout, label: String, min: Double, max: Double, stp: (Double) -> Double,
                         get: () -> Double, set: (Double) -> Unit, ftxt: (Double) -> String, desc: String) {
        val tv = mkText("", 17f)
        tv.gravity = android.view.Gravity.CENTER
        fun bump(dir: Int) {
            val cur = get()
            val v = Math.round(Math.max(min, Math.min(max, cur + dir * stp(if (dir < 0) cur - 0.0001 else cur))) * 1000.0) / 1000.0
            set(v)
            pushDsp()
            tv.removeCallbacks(saveDeb)
            tv.postDelayed(saveDeb, 700)
            refreshSound()
        }
        val r = mkRow()
        r.addView(mkBig("−", 1f, 30f, 72) { bump(-1) })
        r.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.4f))
        r.addView(mkBig("+", 1f, 30f, 72) { bump(1) })
        par.addView(r)
        if (desc.isNotEmpty()) {
            val d = mkText(desc, 12f)
            d.setTextColor(Color.parseColor("#8899AA"))
            d.setPadding(dp(8), 0, dp(8), dp(10))
            par.addView(d)
        }
        refreshers.add { tv.text = label + "\n" + ftxt(get()) }
    }

    private fun wide(b: Button, hDp: Int): Button {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.minHeight = dp(hDp)
        b.minimumHeight = dp(hDp)
        b.setPadding(dp(6), dp(2), dp(6), dp(2))
        fitText(b, 0f, 11f)
        return b
    }

    private fun buildSoundPane(dl: LinearLayout) {
        dl.setPadding(dp(4), dp(4), dp(4), dp(12))
        val bDsp = mkBig("DSP", 1f, 20f, 64) { dspOn = !dspOn; pushDsp(); refreshSound(); saveSettings() }
        val bNorm = mkBig("Ühtlustus", 1f, 20f, 64) { normOn = !normOn; pushDsp(); refreshSound(); saveSettings() }
        val r0 = mkRow(); r0.addView(bDsp); r0.addView(bNorm); dl.addView(r0)
        val info = mkText("", 13f)
        info.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(info)

        val mb = ArrayList<Button>()
        for (i in 0 until 5) {
            val b = wide(mkBtn(W_NAMES[i], 1f) { wMode = i; applyW(true); status(W_NAMES[i]) }, 70)
            b.textSize = 20f
            mb.add(b); dl.addView(b)
        }
        stepper(dl, "Tugevus", 0.0, 100.0, 5.0,
            { if (wMode >= 0) wStr[wMode] else 0.0 },
            { v -> if (wMode >= 0) { wStr[wMode] = v; settings.put("dsp", w211(wMode, v / 100.0)); saveW() } },
            { v -> if (wMode >= 0) v.toInt().toString() + "%" else "vali esmalt režiim" },
            "Režiimi tugevus. + rohkem bassi ja efekti, aga vähem varu ning vokaal võib vajuda. − puhtam ja valjem heli.")
        stepper(dl, "Helitugevus (rakendus)", 0.0, 100.0, 5.0, { vol.toDouble() }, { v -> vol = v.toInt(); applyVol() }, { v -> v.toInt().toString() + "%" },
            "Hoia 100% ja reguleeri autoraadio nupust – nii on heli puhtaim.")
        refreshers.add {
            setOn(bDsp, dspOn); setOn(bNorm, normOn)
            for (i in 0 until 5) setOn(mb[i], i == wMode)
            info.text = "DSP " + (if (dspOn) "sees" else "väljas") + " · ühtlustus " + String.format("%+.1f", normDb(current)) + " dB · siht " + tgt.toInt() + " LUFS"
        }

    }

    private fun buildSettingsPane(dl: LinearLayout) {
        dl.setPadding(dp(4), dp(4), dp(4), dp(12))
        dl.addView(mkHead("Käivitamisel"))
        val bAuto = wide(mkBtn("", 1f) { prefs.edit().putBoolean("auto", !prefs.getBoolean("auto", true)).apply(); refreshSound() }, 56)
        dl.addView(bAuto)
        dl.addView(mkHead("Uni-taimer"))
        val mins = intArrayOf(0, 15, 30, 60)
        val sbs = ArrayList<Button>()
        val rsl = mkRow()
        for (i in 0 until 4) {
            val b = mkBig(if (mins[i] == 0) "Väljas" else mins[i].toString() + " min", 1f, 17f, 56) {
                sleepMin = mins[i]
                sleepEnd = if (mins[i] == 0) 0L else System.currentTimeMillis() + mins[i] * 60000L
                refreshSound()
                status(if (mins[i] == 0) "Uni-taimer väljas" else "Muusika peatub " + mins[i] + " min pärast")
            }
            sbs.add(b); rsl.addView(b)
        }
        dl.addView(rsl)
        refreshers.add {
            bAuto.text = "Jätka viimasest laulust käivitusel: " + (if (prefs.getBoolean("auto", true)) "JAH" else "EI")
            setOn(bAuto, prefs.getBoolean("auto", true))
            for (i in 0 until 4) setOn(sbs[i], sleepMin == mins[i])
        }
        dl.addView(mkHead("Lugude korrastus"))
        dl.addView(wide(mkBtn("Leia dubleerivad lood (kustutamiseks)", 1f) {
            dupOnly = true
            showMode(1)
            selMode = true
            selected.clear()
            refreshList()
        }, 56))
        dl.addView(mkHead("Värv"))
        val rth = mkRow()
        for (i in 0 until 5) { val b = mkBig(THEME_NAMES[i], 1f, 16f, 56) { applyTheme(i) }; b.setTextColor(THEME_FG[i]); rth.addView(b) }
        dl.addView(rth)
        dl.addView(mkHead("Ekraani ääred (kui nurgad pole kasutatavad)"))
        val rm = mkRow()
        rm.addView(mkBig("Külg −", 1f, 17f, 56) { prefs.edit().putInt("mgs", Math.max(0, prefs.getInt("mgs", 40) - 8)).apply(); applyMargins() })
        rm.addView(mkBig("Külg +", 1f, 17f, 56) { prefs.edit().putInt("mgs", Math.min(160, prefs.getInt("mgs", 40) + 8)).apply(); applyMargins() })
        rm.addView(mkBig("Alt −", 1f, 17f, 56) { prefs.edit().putInt("mgb", Math.max(0, prefs.getInt("mgb", 56) - 8)).apply(); applyMargins() })
        rm.addView(mkBig("Alt +", 1f, 17f, 56) { prefs.edit().putInt("mgb", Math.min(200, prefs.getInt("mgb", 56) + 8)).apply(); applyMargins() })
        dl.addView(rm)
        val rt = mkRow()
        rt.addView(mkBig("Üla −", 1f, 17f, 56) { prefs.edit().putInt("mgt", Math.max(0, prefs.getInt("mgt", 20) - 8)).apply(); applyMargins() })
        rt.addView(mkBig("Üla +", 1f, 17f, 56) { prefs.edit().putInt("mgt", Math.min(200, prefs.getInt("mgt", 20) + 8)).apply(); applyMargins() })
        dl.addView(rt)

        dl.addView(mkHead("Muu"))
        dl.addView(wide(mkBtn("Võta seaded serverist", 1f) { loadAll() }, 56))
        dl.addView(wide(mkBtn("Server / PIN", 1f) { askServer() }, 56))
        dl.addView(wide(mkBtn("Tühjenda vahemälu", 1f) { clearCache() }, 56))
        dl.addView(wide(mkBtn("Lae pisipildid vanadele lugudele", 1f) {
            bg.execute {
                try {
                    val r = JSONObject(api.post("/api/thumbs/backfill"))
                    status(if (r.optInt("busy", 0) == 1) "Pisipildid juba laadimas…" else "Pisipildid: otsin " + r.optInt("started", 0) + " loole")
                    pollThumbs(0)
                } catch (e: Exception) { status("Pisipildid: " + (e.message ?: "viga")) }
            }
        }, 56))
        dl.addView(wide(mkBtn("Kontrolli uuendust ja paigalda", 1f) { checkUpdate(true) }, 56))
        val ud = mkText("Rakendus kontrollib uuendust ise iga käivitusel. Uus versioon laetakse alla ja Android küsib paigaldamise kinnitust.", 12f)
        ud.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(ud)
        val ver = mkText("MB Player v" + packageManager.getPackageInfo(packageName, 0).versionName + "  ·  Andres Aareleid  ·  Rakendus.biz", 13f)
        ver.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(ver)
        }

    // ---------- Pro tab: every DSP value by hand ----------
    private var selBand = 0
    private var saveMode = false

    private fun dsp(): JSONObject {
        var d = settings.optJSONObject("dsp")
        if (d == null) { d = defDsp(); settings.put("dsp", d) }
        val e = d.optJSONArray("eq")
        if (e == null || e.length() != DspCfg.EQF.size) d.put("eq", defEq())
        return d
    }

    private fun band(): JSONObject = dsp().getJSONArray("eq").getJSONObject(selBand)

    /** Called when a Pro control changes the sound: the 5 simple modes no longer describe it. */
    private fun touched() {
        if (wMode >= 0) { wMode = -1; saveW() }
    }

    private fun proApply() {
        touched()
        pushDsp()
        refreshSound()
        saveSettings()
    }

    private fun num(k: String, def: Double): Double = dsp().optDouble(k, def)

    private fun sl(par: LinearLayout, label: String, key: String, min: Double, max: Double, step: Double, def: Double, unit: String, desc: String) {
        stepper(par, label, min, max, step, { num(key, def) }, { v -> touched(); dsp().put(key, v) }, { v ->
            (if (step < 1.0) String.format("%.1f", v) else v.toInt().toString()) + unit
        }, desc)
    }

    private fun resetBtn(par: LinearLayout, keys: Array<String>) {
        par.addView(wide(mkBtn("Nulli see rühm", 1f) {
            val d = defDsp()
            for (k in keys) dsp().put(k, d.get(k))
            proApply()
        }, 52))
    }

    private fun userSlots(): JSONArray {
        val a = settings.optJSONArray("pre")
        val r = JSONArray()
        for (i in 0 until 4) r.put(if (a != null && i < a.length() && !a.isNull(i)) a.get(i) else JSONObject.NULL)
        return r
    }

    private fun buildProPane(dl: LinearLayout) {
        dl.setPadding(dp(4), dp(4), dp(4), dp(12))
        val note = mkText("Käsitsi seaded. Iga [−]/[+] vajutus muudab täpselt ühe sammu. Heli-tabi režiimid kirjutavad need üle. Salvestub serverisse.", 13f)
        note.setTextColor(Color.parseColor("#8899AA"))
        dl.addView(note)

        dl.addView(mkHead("Ühtlustus (valjuse võrdsustamine)"))
        val bNorm = mkBig("Ühtlustus", 1f, 20f, 60) { normOn = !normOn; pushDsp(); refreshSound(); saveSettings() }
        dl.addView(bNorm)
        val nd = mkText("Mida teeb: toob kõik lood ligikaudu sama valjuseni. Kasu: ei pea lugude vahel nuppu keerama. Kaotus: valjud lood muutuvad vaiksemaks, DSP tundub vaiksem. Kui tundub vaikne – lülita välja või tõsta sihti.", 12f)
        nd.setTextColor(Color.parseColor("#8899AA")); nd.setPadding(dp(8), 0, dp(8), dp(10))
        dl.addView(nd)
        refreshers.add { setOn(bNorm, normOn) }
        stepper(dl, "Siht (LUFS)", -23.0, -5.0, 1.0, { tgt }, { v -> tgt = v }, { v -> v.toInt().toString() + " LUFS" },
            "+ (nt −7) valjem, aga vähem varu ja rohkem piiramist. − (nt −14) vaiksem, aga puhtam ja dünaamilisem.")
        sl(dl, "Max ühtlustuse tõus", "maxg", 0.0, 15.0, 1.0, 9.0, " dB",
            "Kui palju võib vaikset lugu tõsta. + vaiksed lood tulevad välja; − liiga kõrge tõstab ka müra.")

        dl.addView(mkHead("Minu eelseaded"))
        val ur = mkRow()
        val ubs = ArrayList<Button>()
        for (i in 0 until 4) {
            val b = mkBig("Oma " + (i + 1), 1f, 17f, 60) {
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
                    else { settings.put("dsp", JSONObject(o.toString())); proApply(); status("Laetud: Oma " + (i + 1)) }
                }
            }
            ubs.add(b); ur.addView(b)
        }
        dl.addView(ur)
        val bSave = wide(mkBtn("Salvesta praegune → siis vali Oma 1-4", 1f) { saveMode = !saveMode; refreshSound() }, 56)
        dl.addView(bSave)
        refreshers.add {
            setOn(bSave, saveMode)
            val u = userSlots()
            for (i in 0 until 4) ubs[i].text = "Oma " + (i + 1) + (if (u.isNull(i)) "" else " ●")
        }

        dl.addView(mkHead("Bass"))
        sl(dl, "Sub bass 45 Hz", "sub", 0.0, 12.0, 1.0, 0.0, " dB",
            "Mida teeb: lisab sügavat bassi. Kasu: rütm tuleb kehasse, uksed värisevad. Kaotus: liiga palju moonutab ja surub vokaali alla. Algus 4–8 dB.")
        sl(dl, "Harmoonikud", "enh", 0.0, 100.0, 5.0, 0.0, "%",
            "Mida teeb: lisab bassile ülemtoone. Kasu: bass kostub ka tee- ja mootorimüra sees ning väikestes kõlarites. Kaotus: liiga palju teeb bassi karedaks ja ebaloomulikuks.")
        sl(dl, "Harmoonikute piir", "ecf", 60.0, 200.0, 5.0, 110.0, " Hz",
            "Millest alates ülemtoone tekitatakse. − sügavam ja pehmem; + kuuldavam, aga kõlab kõrgemalt.")
        sl(dl, "Madalpiirang (HP)", "hp", 10.0, 80.0, 2.0, 24.0, " Hz",
            "Lõikab kõlareid kahjustava sügavaima osa. + kaitseb kõlareid, heli selgem ja tihedam; − rohkem sügavust, aga kõlarid võivad moonutada. Soovitus 26–30 Hz.")
        sl(dl, "Kõrgpiirang (LP)", "lp", 3000.0, 20000.0, 1000.0, 20000.0, " Hz",
            "Lõikab kõrged toonid. − pehmem heli, vähem sisinat; + (20000) täielik heli. Muuda vaid kui kõrged on valusad.")
        resetBtn(dl, arrayOf("sub", "enh", "ecf", "hp", "lp"))

        dl.addView(mkHead("Üldine"))
        sl(dl, "Eelvõimendus (pre)", "pre", -12.0, 12.0, 1.0, 0.0, " dB",
            "− annab võimendile ruumi, vähem moonutust; + valjem, aga kipub piirama ja moonutama. Bassi lisamisel mine −1…−3.")
        sl(dl, "Stereo laius", "wid", 0.0, 200.0, 10.0, 100.0, "%",
            "+ avaram lava, 3D-tunne; − kitsam ja kesksem vokaal. Üle 130% võib bassi ja vokaali nõrgendada.")
        val rs = mkRow()
        val bSat = ArrayList<Button>()
        val sn = arrayOf("Küllastus: ei", "Pehme", "Tugev")
        for (i in 0 until 3) { val b = mkBig(sn[i], 1f, 17f, 56) { touched(); dsp().put("sat", i); proApply() }; bSat.add(b); rs.addView(b) }
        dl.addView(rs)
        val sd = mkText("Küllastus lisab soojust ja \"paksust\" helile (kasu: valjem tunne), aga liiga tugev teeb heli räpaseks.", 12f)
        sd.setTextColor(Color.parseColor("#8899AA")); sd.setPadding(dp(8), 0, dp(8), dp(10))
        dl.addView(sd)
        refreshers.add { val sv = dsp().optInt("sat", 0); for (i in 0 until 3) setOn(bSat[i], sv == i) }
        resetBtn(dl, arrayOf("pre", "wid", "sat"))

        dl.addView(mkHead("Equalizer (16 riba)"))
        val bandBtns = ArrayList<Button>()
        for (o in intArrayOf(0, 8)) {
            val r = mkRow()
            for (i in o until o + 8) {
                val b = mkBig("", 1f, 12f, 60) { selBand = i; refreshSound() }
                bandBtns.add(b); r.addView(b)
            }
            dl.addView(r)
        }
        refreshers.add {
            val e = dsp().getJSONArray("eq")
            for (i in 0 until 16) {
                val o = e.getJSONObject(i)
                val f = o.optDouble("f", 0.0)
                bandBtns[i].text = (if (f >= 1000) String.format("%.1fk", f / 1000) else f.toInt().toString()) + "\n" + String.format("%+.1f", o.optDouble("g", 0.0))
                setOn(bandBtns[i], i == selBand)
            }
        }
        val ed = mkText("Vali riba ülalt, siis muuda. Soovitus: 40–90 Hz = bassi löök, 100–250 = soojus/\"tünn\", 1–4 kHz = vokaal, 8 kHz+ = hõrgus.", 12f)
        ed.setTextColor(Color.parseColor("#8899AA")); ed.setPadding(dp(8), 0, dp(8), dp(6))
        dl.addView(ed)
        stepperF(dl, "Riba sagedus", 20.0, 16000.0,
            { f -> if (f < 100) 5.0 else if (f < 300) 10.0 else if (f < 1000) 50.0 else if (f < 3000) 100.0 else if (f < 10000) 500.0 else 1000.0 },
            { band().optDouble("f", 100.0) }, { v -> touched(); band().put("f", v) }, { v -> v.toInt().toString() + " Hz" },
            "Kus riba mõjub. Ära liiguta alla 30 Hz – kõlarid ei suuda seda.")
        stepper(dl, "Riba võimendus", -15.0, 15.0, 0.5, { band().optDouble("g", 0.0) }, { v -> touched(); band().put("g", v) }, { v -> String.format("%+.1f dB", v) },
            "+ tõstab selle ala (rohkem jõudu, aga ka moonutust); − vaigistab (puhtam, aga heli õhem). Üle +6 dB ole ettevaatlik.")
        stepper(dl, "Riba Q (laius)", 0.3, 6.0, 0.1, { band().optDouble("q", 1.0) }, { v -> touched(); band().put("q", v) }, { v -> String.format("%.1f", v) },
            "Väike Q = lai ja loomulik muutus; suur Q = kitsas, täpne, aga võib kõlada \"torujalt\".")
        val re = mkRow()
        re.addView(mkBig("Nulli riba", 1f, 17f, 56) {
            band().put("g", 0.0); band().put("f", DspCfg.EQF[selBand])
            band().put("q", if (selBand == 0 || selBand == 15) 0.7 else 1.2); proApply()
        })
        re.addView(mkBig("Nulli kõik ribad", 1f, 17f, 56) { dsp().put("eq", defEq()); proApply() })
        dl.addView(re)

        dl.addView(mkHead("Kompressor"))
        val cd = mkText("Mida teeb: surub valjud kohad alla. Kasu: ühtlasem, tihedam heli. Kaotus: kui bass lööb, vajub vokaal alla ja heli muutub \"lamedaks\". Kui vokaal ei püsi – lülita välja.", 12f)
        cd.setTextColor(Color.parseColor("#8899AA")); cd.setPadding(dp(8), 0, dp(8), dp(6))
        dl.addView(cd)
        val rc = mkRow()
        val cb = ArrayList<Button>()
        val cn = arrayOf("Väljas", "Sisse", "Tugev")
        for (i in 0 until 3) { val b = mkBig(cn[i], 1f, 17f, 56) { touched(); dsp().put("cm", i); proApply() }; cb.add(b); rc.addView(b) }
        dl.addView(rc)
        refreshers.add { val sv = dsp().optInt("cm", 0); for (i in 0 until 3) setOn(cb[i], sv == i) }
        sl(dl, "Lävi", "thr", -50.0, 0.0, 2.0, -24.0, " dB",
            "Alates millest hakkab suruma. + (nt −6) surub vähe; − (nt −30) surub palju ja heli läheb vaiksemaks.")
        sl(dl, "Suhe", "rat", 1.0, 10.0, 0.5, 3.0, ":1",
            "Kui tugevalt surub. Väike (1.5) pehme ja loomulik; suur (6+) kõva ja lame.")
        sl(dl, "Rünnak", "att", 1.0, 100.0, 5.0, 20.0, " ms",
            "Kui kiiresti surub. Kiire = kaitseb piikide eest, aga tapab löögi; aeglane = löögid jäävad terveks.")
        sl(dl, "Vabanemine", "rel", 20.0, 1000.0, 20.0, 220.0, " ms",
            "Kui kiiresti lasku lahti. Kiire = elav, aga võib \"pumbata\"; aeglane = sujuv, aga heli jääb pikaks ajaks vaikseks.")
        resetBtn(dl, arrayOf("cm", "thr", "rat", "att", "rel", "knee", "mk", "mka"))

        dl.addView(mkHead("Limiter"))
        sl(dl, "Lagi", "lc", -6.0, 0.0, 0.5, -1.0, " dB",
            "Kõva lagi, mis kaitseb moonutuse eest. − (nt −3) turvalisem, aga vaiksem ja surub kogu heli; + (0) valjem, aga võib krõbiseda.")
        sl(dl, "Vabanemine", "lr", 20.0, 500.0, 10.0, 120.0, " ms",
            "Kui kiiresti heli pärast piiramist tagasi tuleb. Kiire = loomulikum; aeglane = ühtlasem, aga bassilöögi järel vajub kõik hetkeks alla.")
        resetBtn(dl, arrayOf("lc", "lr"))
    }

    private fun applyRepeat() {
        btnRep.text = when (repMode) { 0 -> "KORDA väljas"; 2 -> "KORDA 1 lugu"; else -> "KORDA kõik" }
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

    override fun onPause() {
        super.onPause()
        saveLast()
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
