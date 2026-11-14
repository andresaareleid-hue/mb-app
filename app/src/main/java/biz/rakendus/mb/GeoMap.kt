package biz.rakendus.mb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class LatLon(val lat: Double, val lon: Double)

object Geo {
    fun dist(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dp = p2 - p1
        val dl = Math.toRadians(bLon - aLon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

    fun fmtDist(m: Double): String {
        if (m >= 1000.0) return String.format("%.1f km", m / 1000.0)
        if (m >= 200.0) return (Math.round(m / 50.0) * 50).toString() + " m"
        return (Math.round(m / 10.0) * 10).toString() + " m"
    }

    fun speechDist(m: Double): String {
        if (m >= 1000.0) return String.format("%.1f kilomeetri", m / 1000.0)
        return (Math.round(m / 50.0) * 50).toString() + " meetri"
    }
}

class Step(val type: String, val mod: String, val name: String, val dist: Double, val lat: Double, val lon: Double, val exit: Int,
           val ib: IntArray = IntArray(0), val ie: IntArray = IntArray(0), val inIdx: Int = -1, val outIdx: Int = -1,
           val lanes: List<Pair<Boolean, String>> = emptyList()) {
    fun hasPreview(): Boolean = (ib.size >= 3 && inIdx in ib.indices && outIdx in ib.indices) || lanes.isNotEmpty()

    fun angle(): Float {
        return when (mod) {
            "slight right" -> 35f
            "right" -> 90f
            "sharp right" -> 140f
            "uturn" -> 180f
            "sharp left" -> -140f
            "left" -> -90f
            "slight left" -> -35f
            else -> 0f
        }
    }

    private fun turnWord(): String {
        return when (mod) {
            "left" -> "Pööra vasakule"
            "right" -> "Pööra paremale"
            "slight left" -> "Hoia vasakule"
            "slight right" -> "Hoia paremale"
            "sharp left" -> "Pööra järsult vasakule"
            "sharp right" -> "Pööra järsult paremale"
            "uturn" -> "Tee U-pööre"
            else -> "Sõida otse"
        }
    }

    private fun side(): String {
        return if (mod.contains("left")) "vasakule" else if (mod.contains("right")) "paremale" else "otse"
    }

    fun text(): String {
        val road = if (name.isNotEmpty()) " – " + name else ""
        return when (type) {
            "depart" -> "Alusta sõitu" + road
            "arrive" -> if (mod.contains("left")) "Sihtkoht on vasakul" else if (mod.contains("right")) "Sihtkoht on paremal" else "Jõuad sihtkohta"
            "roundabout", "rotary", "roundabout turn" -> if (exit > 0) "Ringil võta " + exit + ". väljasõit" + road else "Sõida ringteele" + road
            "merge" -> "Liitu liiklusega" + road
            "on ramp" -> "Sõida " + side() + " ligipääsuteele" + road
            "off ramp" -> "Sõida " + side() + " mahasõidule" + road
            "fork" -> "Hargnemisel hoia " + side() + road
            "end of road" -> "Tee lõpus pööra " + side() + road
            "continue", "new name" -> (if (mod == "straight" || mod.isEmpty()) "Jätka otse" else turnWord()) + road
            else -> turnWord() + road
        }
    }
}

class Route(val pts: List<LatLon>, val steps: List<Step>, val dist: Double, val dur: Double) {
    var tag: String = ""
    val cum = DoubleArray(pts.size)
    val stepStart = DoubleArray(steps.size)

    init {
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + Geo.dist(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        val tot = if (cum.isNotEmpty()) cum[cum.size - 1] else 0.0
        val k = if (tot > 1.0 && dist > 1.0) tot / dist else 1.0
        var acc = 0.0
        for (i in steps.indices) {
            stepStart[i] = acc * k
            acc += steps[i].dist
        }
    }

    fun total(): Double = if (cum.isNotEmpty()) cum[cum.size - 1] else 0.0

    companion object {
        fun fromJson(o: JSONObject): Route {
            val pa = o.getJSONArray("pts")
            val pts = ArrayList<LatLon>()
            for (i in 0 until pa.length()) {
                val q = pa.getJSONArray(i)
                pts.add(LatLon(q.getDouble(0), q.getDouble(1)))
            }
            val sa = o.getJSONArray("steps")
            val steps = ArrayList<Step>()
            for (i in 0 until sa.length()) {
                val s = sa.getJSONObject(i)
                var ib = IntArray(0)
                var ie = IntArray(0)
                val ibA = s.optJSONArray("ib")
                if (ibA != null) { ib = IntArray(ibA.length()) { j -> ibA.optInt(j) } }
                val ieA = s.optJSONArray("ie")
                if (ieA != null) { ie = IntArray(ieA.length()) { j -> ieA.optInt(j) } }
                val lnA = s.optJSONArray("ln")
                val lanes = ArrayList<Pair<Boolean, String>>()
                if (lnA != null) {
                    for (j in 0 until lnA.length()) {
                        val l = lnA.optJSONArray(j) ?: continue
                        lanes.add(Pair(l.optInt(0) == 1, l.optString(1)))
                    }
                }
                steps.add(Step(s.optString("t"), s.optString("m"), s.optString("n"), s.optDouble("d", 0.0), s.optDouble("la", 0.0), s.optDouble("lo", 0.0), s.optInt("x", 0),
                    ib, ie, s.optInt("ii", -1), s.optInt("io", -1), lanes))
            }
            val rr = Route(pts, steps, o.optDouble("dist", 0.0), o.optDouble("dur", 0.0))
            rr.tag = o.optString("tag", "")
            return rr
        }
    }
}

class NavInfo(val step: Step?, val stepIdx: Int, val toNext: Double, val remaining: Double, val etaSec: Double, val off: Double, val arrived: Boolean)

class Nav(val route: Route) {
    private var hint = 0
    val index: Int get() = hint

    fun update(lat: Double, lon: Double): NavInfo {
        val n = route.pts.size
        val kx = Math.cos(Math.toRadians(lat)) * 111320.0
        val ky = 110574.0
        var bestD = Double.MAX_VALUE
        var bestS = 0.0
        var bestI = hint

        fun scan(from: Int, to: Int) {
            var i = from
            while (i < to - 1) {
                val ax = (route.pts[i].lon - lon) * kx
                val ay = (route.pts[i].lat - lat) * ky
                val bx = (route.pts[i + 1].lon - lon) * kx
                val by = (route.pts[i + 1].lat - lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                var t = if (len2 < 1e-6) 0.0 else (-ax * dx - ay * dy) / len2
                if (t < 0.0) t = 0.0
                if (t > 1.0) t = 1.0
                val px = ax + t * dx
                val py = ay + t * dy
                val d = Math.sqrt(px * px + py * py)
                if (d < bestD) {
                    bestD = d
                    bestI = i
                    bestS = route.cum[i] + t * (route.cum[i + 1] - route.cum[i])
                }
                i++
            }
        }

        val from = Math.max(0, hint - 3)
        val to = Math.min(n, hint + 500)
        scan(from, to)
        if (bestD > 80.0 && (from > 0 || to < n)) scan(0, n)
        hint = bestI

        val total = route.total()
        val remaining = Math.max(0.0, total - bestS)
        var j = -1
        for (i in 1 until route.steps.size) {
            if (route.stepStart[i] > bestS + 8.0) { j = i; break }
        }
        val arrived = remaining < 25.0 || j < 0
        val eta = if (total > 1.0) route.dur * remaining / total else 0.0
        val st: Step? = if (j >= 0) route.steps[j] else null
        val toNext = if (j >= 0) route.stepStart[j] - bestS else 0.0
        return NavInfo(st, j, toNext, remaining, eta, bestD, arrived)
    }
}

/** Big turn arrow drawn with canvas (no font glyphs needed). kind: 0 arrow, 1 destination, 2 roundabout. */
class TurnView(ctx: Context) : View(ctx) {
    var angle = 0f
    var kind = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = Math.min(w, h)
        c.save()
        c.translate(w / 2f, h / 2f)
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        if (kind == 1) {
            c.drawCircle(0f, 0f, s * 0.34f, p)
            p.color = Color.rgb(0xC0, 0x39, 0x2B)
            c.drawCircle(0f, 0f, s * 0.17f, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = s * 0.15f
            c.drawLine(0f, s * 0.40f, 0f, 0f, p)
            if (kind == 2) {
                p.strokeWidth = s * 0.07f
                c.drawCircle(0f, 0f, s * 0.12f, p)
                p.strokeWidth = s * 0.15f
            }
            c.rotate(angle)
            c.drawLine(0f, 0f, 0f, -s * 0.22f, p)
            p.style = Paint.Style.FILL
            val path = Path()
            path.moveTo(0f, -s * 0.46f)
            path.lineTo(s * 0.27f, -s * 0.16f)
            path.lineTo(-s * 0.27f, -s * 0.16f)
            path.close()
            c.drawPath(path, p)
        }
        c.restore()
    }
}

/** Junction preview: the roads meeting at the next maneuver (route highlighted) plus lane arrows when known. */
class JunctionView(ctx: Context) : View(ctx) {
    private var step: Step? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tri = Path()
    private val rr = RectF()

    fun setStep(s: Step?) { step = s; invalidate() }

    private fun arrowHead(c: Canvas, x: Float, y: Float, deg: Float, size: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(deg)
        tri.reset()
        tri.moveTo(0f, -size)
        tri.lineTo(size * 0.75f, size * 0.5f)
        tri.lineTo(-size * 0.75f, size * 0.5f)
        tri.close()
        p.style = Paint.Style.FILL
        c.drawPath(tri, p)
        c.restore()
    }

    private fun laneAngle(ind: String): Float? = when (ind) {
        "straight" -> 0f
        "slight right" -> 30f
        "right" -> 75f
        "sharp right" -> 130f
        "slight left" -> -30f
        "left" -> -75f
        "sharp left" -> -130f
        "uturn" -> 180f
        else -> null
    }

    override fun onDraw(c: Canvas) {
        val s = step ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.argb(215, 16, 24, 34)
        rr.set(0f, 0f, w, h)
        c.drawRoundRect(rr, 14f, 14f, p)
        val lanesH = if (s.lanes.isNotEmpty()) h * 0.26f else 0f
        val zoneH = h - lanesH
        if (s.ib.size >= 3 && s.inIdx in s.ib.indices && s.outIdx in s.ib.indices) {
            val cx = w / 2f
            val cy = zoneH * 0.55f
            val rad = Math.min(w * 0.46f, zoneH * 0.46f)
            val rot = 180f - s.ib[s.inIdx]
            p.strokeCap = Paint.Cap.ROUND
            // side roads first
            for (i in s.ib.indices) {
                if (i == s.inIdx || i == s.outIdx) continue
                val a = Math.toRadians((s.ib[i] + rot).toDouble())
                val open = i < s.ie.size && s.ie[i] == 1
                p.style = Paint.Style.STROKE
                p.strokeWidth = rad * 0.13f
                p.color = if (open) Color.rgb(0x8A, 0x97, 0xA6) else Color.rgb(0x4B, 0x56, 0x62)
                c.drawLine(cx, cy, cx + (Math.sin(a) * rad).toFloat(), cy - (Math.cos(a) * rad).toFloat(), p)
            }
            // route: in-road and out-road
            val ai = Math.toRadians((s.ib[s.inIdx] + rot).toDouble())
            val ao = Math.toRadians((s.ib[s.outIdx] + rot).toDouble())
            val ix = cx + (Math.sin(ai) * rad).toFloat()
            val iy = cy - (Math.cos(ai) * rad).toFloat()
            val ox = cx + (Math.sin(ao) * rad * 0.82).toFloat()
            val oy = cy - (Math.cos(ao) * rad * 0.82).toFloat()
            p.style = Paint.Style.STROKE
            p.color = Color.WHITE
            p.strokeWidth = rad * 0.27f
            c.drawLine(ix, iy, cx, cy, p)
            c.drawLine(cx, cy, ox, oy, p)
            p.color = Color.rgb(0x2E, 0x8B, 0xFF)
            p.strokeWidth = rad * 0.17f
            c.drawLine(ix, iy, cx, cy, p)
            c.drawLine(cx, cy, ox, oy, p)
            p.color = Color.rgb(0x2E, 0x8B, 0xFF)
            arrowHead(c, cx + (Math.sin(ao) * rad).toFloat(), cy - (Math.cos(ao) * rad).toFloat(), (s.ib[s.outIdx] + rot), rad * 0.26f)
        }
        if (s.lanes.isNotEmpty()) {
            val n = s.lanes.size
            val cw = Math.min(w / n, lanesH * 1.0f)
            val x0 = (w - cw * n) / 2f
            val top = zoneH + lanesH * 0.06f
            val ch = lanesH * 0.88f
            for (i in 0 until n) {
                val (valid, ind) = s.lanes[i]
                val lx = x0 + i * cw
                p.style = Paint.Style.FILL
                p.color = if (valid) Color.argb(235, 0x1F, 0x6F, 0xEB) else Color.argb(235, 0x3A, 0x44, 0x50)
                rr.set(lx + 2f, top, lx + cw - 2f, top + ch)
                c.drawRoundRect(rr, 6f, 6f, p)
                p.color = if (valid) Color.WHITE else Color.rgb(0x8A, 0x97, 0xA6)
                val mx = lx + cw / 2f
                val my = top + ch / 2f
                val r = Math.min(cw, ch) * 0.34f
                for (word in ind.split(",")) {
                    val ang = laneAngle(word.trim()) ?: continue
                    c.save()
                    c.translate(mx, my)
                    c.rotate(ang)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = r * 0.28f
                    c.drawLine(0f, r * 0.9f, 0f, -r * 0.2f, p)
                    c.restore()
                    arrowHead(c, mx + (Math.sin(Math.toRadians(ang.toDouble())) * r * 0.9).toFloat(), my - (Math.cos(Math.toRadians(ang.toDouble())) * r * 0.9).toFloat(), ang, r * 0.5f)
                }
            }
        }
    }
}

/** Minimal slippy-map view: OSM tiles through our server (cached), position arrow, route line. */
class MapView(ctx: Context, private val api: Api, private val tileDir: File) : View(ctx) {
    var zoom = 15
    var centerLat = 59.437
    var centerLon = 24.754
    var posLat = Double.NaN
    var posLon = Double.NaN
    var heading = 0f
    var headingUp = true
    var follow = true
    var route: List<LatLon> = emptyList()
        set(v) { field = v; rz = -1; routeStart = 0 }
    var routeStart = 0
    /** Route preview: candidate routes drawn together, the selected one on top. */
    var alts: List<List<LatLon>> = emptyList()
    var altSel = 0
    var preview = false
    private val altPath = Path()
    var tilt = true
    var onUserZoom: (() -> Unit)? = null
    private var night = false
    private var sat = false
    private var zf = 1f
    private val tm = Matrix()
    private val tinv = Matrix()
    private var tLat = Double.NaN
    private var tLon = 0.0
    private var tHdg = 0f
    private var tSpd = 0f
    private var tAt = 0L
    private var isAtt = false
    var style = "voyager"
    private var rz = -1
    private var rx = DoubleArray(0)
    private var ry = DoubleArray(0)
    var dest: LatLon? = null
    var onLongPress: ((Double, Double) -> Unit)? = null
    var onUserPan: (() -> Unit)? = null
    @Volatile var okCount = 0
    @Volatile var failCount = 0
    @Volatile var lastErr = ""

    private val ts: Float = 256f * 1.35f * Math.max(1f, ctx.resources.displayMetrics.density * 0.8f)
    private val mem = LruCache<String, Bitmap>(110)
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sf = 1f
    private val rf = RectF()
    private val rs = android.graphics.Rect()
    private val routePath = Path()
    private val arrowPath = Path()
    private val wanted = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var frameGen = 0
    private val renderPool = Executors.newSingleThreadExecutor { r -> val t = Thread(r, "mb-render"); t.priority = Thread.NORM_PRIORITY - 1; t }
    private val corPool = Executors.newSingleThreadExecutor { r -> val t = Thread(r, "mb-prefetch"); t.priority = Thread.MIN_PRIORITY; t }
    @Volatile private var corToken = 0
    private var grid = arrayOfNulls<Bitmap>(0)
    private var gridZ = -1
    private var gridStyle = ""
    private var gridX0 = 0
    private var gridY0 = 0

    // performance profile: 0 normal, 1 performance, 2 ultra-light
    var profile = 0
        private set
    private var tickMs = 66L
    private var prefetchN = 2
    var onAutoProfile: ((Int) -> Unit)? = null
    private var lastDraw = 0L
    private var slowCount = 0
    var avgGap = 0f
    var fps = 0f
    private var fpsT = 0L
    private var fpsN = 0
    @Volatile var renderMsAvg = 0f
    @Volatile private var renderCount = 0L
    @Volatile var statDrawn = 0
    @Volatile var statMissing = 0

    fun setProfile(p: Int) {
        profile = p
        when (p) {
            0 -> { tickMs = 66L; prefetchN = 2; mem.resize(110) }
            1 -> { tickMs = 100L; prefetchN = 1; mem.resize(60) }
            else -> { tickMs = 150L; prefetchN = 0; mem.resize(36) }
        }
        if (p == 2) corToken++
        gridZ = -1
        applyStyle()
        invalidate()
    }

    fun trimMem(level: Int) {
        if (level >= 80) mem.evictAll() else mem.trimToSize(Math.max(8, mem.size() / 2))
        gridZ = -1
    }

    fun diag(): String {
        val d = statDrawn
        val m = statMissing
        statDrawn = 0
        statMissing = 0
        val hit = if (d + m > 0) (100 * d / (d + m)) else 100
        val q = synchronized(pending) { pending.size }
        return "FPS " + Math.round(fps) + " · kaadri vahe " + Math.round(avgGap) + " ms\nplaate RAM-is " + mem.size() + " · tabamus " + hit + "%\ngenereerimine " + Math.round(renderMsAvg) + " ms · järjekord " + q + "\nprofiil " + (if (profile == 0) "NORMAL" else if (profile == 1) "PERFORMANCE" else "ULTRA-LIGHT")
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (sd.isInProgress) return false
            val th = if (headingUp) Math.toRadians(heading.toDouble()) else 0.0
            val wx = dx * Math.cos(th) - dy * Math.sin(th)
            val wy = dx * Math.sin(th) + dy * Math.cos(th)
            val n = Math.pow(2.0, zoom.toDouble())
            val cx = lonToX(centerLon, zoom) + wx / ts
            val cy = latToY(centerLat, zoom) + wy / ts
            centerLon = cx / n * 360.0 - 180.0
            centerLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * cy / n))))
            if (follow) { follow = false; onUserPan?.invoke() }
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val th = if (headingUp) Math.toRadians(heading.toDouble()) else 0.0
            var ex = e.x
            var ey = e.y
            if (tiltOn()) {
                val pt = floatArrayOf(ex, ey)
                if (tm.invert(tinv)) { tinv.mapPoints(pt); ex = pt[0]; ey = pt[1] }
            }
            val ax = ex - width / 2f
            val ay = ey - anchorY() * height
            val wx = ax * Math.cos(th) - ay * Math.sin(th)
            val wy = ax * Math.sin(th) + ay * Math.cos(th)
            val n = Math.pow(2.0, zoom.toDouble())
            val cx = lonToX(centerLon, zoom) + wx / ts
            val cy = latToY(centerLat, zoom) + wy / ts
            val lon = cx / n * 360.0 - 180.0
            val lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * cy / n))))
            onLongPress?.invoke(lat, lon)
        }
    })

    private val sd: ScaleGestureDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { sf = 1f; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { sf *= d.scaleFactor; return true }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (sf > 1.3f) zoomBy(1) else if (sf < 0.77f) zoomBy(-1)
        }
    })

    init {
        trimDisk()
    }

    private fun trimDisk() {
        pool.execute {
            try {
                val fs = tileDir.listFiles() ?: return@execute
                var total = 0L
                for (f in fs) total += f.length()
                if (total > 150L * 1024 * 1024) {
                    val sorted = fs.sortedBy { it.lastModified() }
                    for (f in sorted) {
                        total -= f.length()
                        f.delete()
                        if (total < 110L * 1024 * 1024) break
                    }
                }
            } catch (e: Throwable) {}
        }
    }

    fun setNight(on: Boolean) { night = on; applyStyle() }
    fun setSat(on: Boolean) { sat = on; applyStyle() }
    fun setOffline(on: Boolean) { offline = on; applyStyle() }
    private var offline = false
    private val tstyle: String get() = style

    private fun applyStyle() {
        val ns = if (sat) "sat" else if (offline) (if (profile == 2) (if (night) "offld" else "offl") else (if (night) "offd" else "off")) else if (night) "dark" else "voyager"
        if (ns != style) { style = ns; invalidate() }
    }

    private fun tiltOn(): Boolean = tilt && follow && headingUp && profile == 0 && !preview

    /** New GPS fix: the view glides toward it and predicts ahead by speed (dead reckoning). */
    fun setFix(lat: Double, lon: Double, spd: Float, hdg: Float) {
        tLat = lat; tLon = lon; tHdg = hdg
        tSpd = if (spd < 1.0f) 0f else spd
        tAt = SystemClock.uptimeMillis()
    }

    private val ticker = object : Runnable {
        override fun run() {
            step()
            if (isAtt) postDelayed(this, tickMs)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAtt = true
        removeCallbacks(ticker)
        postDelayed(ticker, tickMs)
    }

    override fun onDetachedFromWindow() {
        isAtt = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun step() {
        var ch = false
        if (!tLat.isNaN()) {
            var dt = (SystemClock.uptimeMillis() - tAt) / 1000.0
            if (dt > 2.5) dt = 2.5
            val d = tSpd * dt
            val hr = Math.toRadians(tHdg.toDouble())
            val pl = tLat + d * Math.cos(hr) / 110574.0
            val po = tLon + d * Math.sin(hr) / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(tLat))))
            if (posLat.isNaN()) {
                posLat = pl; posLon = po; heading = tHdg; ch = true
            } else {
                val nl = posLat + (pl - posLat) * 0.4
                val no = posLon + (po - posLon) * 0.4
                if (Math.abs(nl - posLat) > 1e-9 || Math.abs(no - posLon) > 1e-9) ch = true
                posLat = nl; posLon = no
                val dh = ((tHdg - heading + 540f) % 360f) - 180f
                if (Math.abs(dh) > 0.3f) { heading = (heading + dh * 0.2f + 360f) % 360f; ch = true }
            }
            if (follow && ch) { centerLat = posLat; centerLon = posLon }
        }
        if (zf != 1f) {
            val l = Math.log(zf.toDouble()) * 0.72
            zf = if (Math.abs(l) < 0.02) 1f else Math.exp(l).toFloat()
            ch = true
        }
        if (ch) invalidate()
    }

    fun setPos(lat: Double, lon: Double, hdg: Float) {
        posLat = lat
        posLon = lon
        heading = hdg
        if (follow) { centerLat = lat; centerLon = lon }
        invalidate()
    }

    /** Frame all given points on screen (upper part, the bottom panel covers the rest). */
    fun fitBounds(all: List<List<LatLon>>) {
        var mnLa = 90.0; var mxLa = -90.0; var mnLo = 180.0; var mxLo = -180.0
        for (pl in all) for (q in pl) {
            if (q.lat < mnLa) mnLa = q.lat; if (q.lat > mxLa) mxLa = q.lat
            if (q.lon < mnLo) mnLo = q.lon; if (q.lon > mxLo) mxLo = q.lon
        }
        if (mxLa < mnLa) return
        val w = Math.max(1, width).toDouble()
        val h = Math.max(1, height).toDouble()
        var z = 17
        while (z > 5) {
            val bw = (lonToX(mxLo, z) - lonToX(mnLo, z)) * ts
            val bh = (latToY(mnLa, z) - latToY(mxLa, z)) * ts
            if (bw <= w * 0.72 && bh <= h * 0.5) break
            z--
        }
        follow = false
        val cxm = (lonToX(mnLo, z) + lonToX(mxLo, z)) / 2.0
        val cym = (latToY(mnLa, z) + latToY(mxLa, z)) / 2.0 + 0.16 * h / ts
        val n = Math.pow(2.0, z.toDouble())
        centerLon = cxm / n * 360.0 - 180.0
        centerLat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * cym / n))))
        zf = 1f
        zoom = z
        invalidate()
    }

    fun zoomBy(d: Int) {
        setZoomAbs(zoom + d)
        onUserZoom?.invoke()
    }

    /** Smooth zoom: switch level and animate the scale from the old size. */
    fun setZoomAbs(nz: Int) {
        val z2 = Math.max(4, Math.min(18, nz))
        if (z2 == zoom) return
        zf = Math.max(0.25f, Math.min(4f, zf * Math.pow(2.0, (zoom - z2).toDouble()).toFloat()))
        zoom = z2
        invalidate()
    }

    private fun anchorY(): Float = if (follow && headingUp) 0.68f else 0.5f

    private fun lonToX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * Math.pow(2.0, z.toDouble())

    private fun latToY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * Math.pow(2.0, z.toDouble())
    }

    private fun tileFile(st: String, z: Int, x: Int, y: Int): File =
        if (st.startsWith("off")) File(tileDir, st + "3-" + z + "-" + x + "-" + y + ".jpg")
        else File(tileDir, st + "-" + z + "-" + x + "-" + y + ".png")

    private fun stale(k: String): Boolean {
        val g = wanted[k] ?: return false
        return frameGen - g > 3
    }

    private fun done(k: String, ok: Boolean, skipped: Boolean) {
        synchronized(pending) {
            pending.remove(k)
            if (!ok && !skipped) { failed[k] = System.currentTimeMillis(); failCount++ }
        }
        if (ok) postInvalidate()
    }

    private fun saveJpg(b: Bitmap, f: File) {
        try { java.io.FileOutputStream(f).use { os -> b.compress(Bitmap.CompressFormat.JPEG, 88, os) } } catch (e: Throwable) {}
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val st = style
        val k = st + "/" + z + "/" + x + "/" + y
        wanted[k] = frameGen
        val hit = mem.get(k)
        if (hit != null) return hit
        val now = System.currentTimeMillis()
        synchronized(pending) {
            val fl = failed[k]
            if (fl != null && now - fl < 20000L) return null
            if (!pending.add(k)) return null
        }
        if (wanted.size > 600) { val g0 = frameGen - 30; wanted.entries.removeAll { it.value < g0 } }
        pool.execute { loadTile(k, st, z, x, y) }
        return null
    }

    /** Runs on the small IO pool: disk cache hit, or hand over to the single render thread / the network. */
    private fun loadTile(k: String, st: String, z: Int, x: Int, y: Int) {
        try {
            if (stale(k)) { done(k, false, true); return }
            val f = tileFile(st, z, x, y)
            if (st.startsWith("off")) {
                var b: Bitmap? = null
                if (f.exists()) {
                    val o2 = BitmapFactory.Options()
                    o2.inPreferredConfig = Bitmap.Config.RGB_565
                    b = BitmapFactory.decodeFile(f.path, o2)
                }
                if (b != null) { mem.put(k, b); okCount++; done(k, true, false); return }
                renderPool.execute { renderTile(k, st, z, x, y) }
                return
            }
            var bytes: ByteArray? = null
            if (f.exists()) bytes = f.readBytes()
            if (bytes == null) {
                bytes = api.bytes("/api/tile?s=" + st + "&z=" + z + "&x=" + x + "&y=" + y)
                if (bytes != null) { try { f.writeBytes(bytes) } catch (e: Throwable) {} } else lastErr = "server ei anna plaati"
            }
            var ok = false
            if (bytes != null) {
                val o = BitmapFactory.Options()
                o.inPreferredConfig = Bitmap.Config.RGB_565
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (b != null) { mem.put(k, b); ok = true; okCount++ } else lastErr = "vigane pilt"
            }
            done(k, ok, false)
        } catch (e: Throwable) {
            done(k, false, false)
        }
    }

    /** Single low-priority render thread: only tiles still wanted by the screen are drawn. */
    private fun renderTile(k: String, st: String, z: Int, x: Int, y: Int) {
        try {
            if (stale(k)) { done(k, false, true); return }
            val t0 = SystemClock.uptimeMillis()
            val rb = OfflineMap.render(z, x, y, st.endsWith("d"), st == "offl" || st == "offld")
            val dt = SystemClock.uptimeMillis() - t0
            renderCount++
            renderMsAvg = if (renderCount == 1L) dt.toFloat() else renderMsAvg * 0.9f + dt * 0.1f
            if (rb != null) {
                mem.put(k, rb)
                okCount++
                done(k, true, false)
                val f = tileFile(st, z, x, y)
                pool.execute { saveJpg(rb, f) }
            } else {
                lastErr = "offline: " + OfflineMap.err
                done(k, false, false)
            }
        } catch (e: Throwable) {
            done(k, false, false)
        }
    }

    /** Idle-time pre-rendering of the tiles along the route (disk cache only, lowest priority). */
    fun prefetchRoute(pts: List<LatLon>) {
        val tok = ++corToken
        if (profile == 2 || pts.size < 2) return
        corPool.execute {
            try {
                val order = ArrayList<IntArray>()
                val seen = HashSet<Long>()
                var dist = 0.0
                var next = 0.0
                var prev = pts[0]
                for (p in pts) {
                    dist += Geo.dist(prev.lat, prev.lon, p.lat, p.lon)
                    prev = p
                    if (dist < next) continue
                    next = dist + 140.0
                    if (dist > 40000.0 || order.size > 900) break
                    for (zz in intArrayOf(16, 15, 14)) {
                        val nn = 1 shl zz
                        val cxx = Math.floor(lonToX(p.lon, zz)).toInt()
                        val cyy = Math.floor(latToY(p.lat, zz)).toInt()
                        val r = if (zz == 14) 0 else 1
                        for (dy in -r..r) for (dx in -r..r) {
                            val tx = cxx + dx
                            val ty = cyy + dy
                            if (tx < 0 || ty < 0 || tx >= nn || ty >= nn) continue
                            val key = (zz.toLong() shl 50) or (tx.toLong() shl 25) or ty.toLong()
                            if (seen.add(key)) order.add(intArrayOf(zz, tx, ty))
                        }
                    }
                }
                for (t in order) {
                    if (tok != corToken) return@execute
                    while (tok == corToken && synchronized(pending) { pending.isNotEmpty() }) Thread.sleep(250)
                    if (tok != corToken) return@execute
                    val st = style
                    if (!st.startsWith("off")) return@execute
                    val f = tileFile(st, t[0], t[1], t[2])
                    if (f.exists()) continue
                    val rb = OfflineMap.render(t[0], t[1], t[2], st.endsWith("d"), st == "offl" || st == "offld") ?: continue
                    saveJpg(rb, f)
                    Thread.sleep(30)
                }
            } catch (e: Throwable) {}
        }
    }

    private fun prefetchAhead(z: Int, n: Int) {
        val meters = Math.max(200.0, tSpd * 10.0)
        val hr = Math.toRadians(tHdg.toDouble())
        val aLat = posLat + meters * Math.cos(hr) / 110574.0
        val aLon = posLon + meters * Math.sin(hr) / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(posLat))))
        val ax = Math.floor(lonToX(aLon, z)).toInt()
        val ay = Math.floor(latToY(aLat, z)).toInt()
        var cnt = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (cnt >= prefetchN * 3) return
            val ty = ay + dy
            if (ty < 0 || ty >= n) continue
            tile(z, ((ax + dx) % n + n) % n, ty)
            cnt++
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        gd.onTouchEvent(e)
        return true
    }

    private var ct0 = 0.0
    private var ct1 = 1.0

    private fun clipT(p: Double, q: Double): Boolean {
        if (p == 0.0) return q >= 0.0
        val r = q / p
        if (p < 0.0) { if (r > ct1) return false; if (r > ct0) ct0 = r }
        else { if (r < ct0) return false; if (r < ct1) ct1 = r }
        return true
    }

    private fun drawFallback(c: Canvas, z: Int, tx: Int, ty: Int, dx: Float, dy: Float, sz: Float) {
        val pb = mem.get(tstyle + "/" + (z - 1) + "/" + (tx shr 1) + "/" + (ty shr 1))
        if (pb != null) {
            val hw = pb.width / 2
            val hh = pb.height / 2
            val sx = (tx and 1) * hw
            val sy = (ty and 1) * hh
            rs.set(sx, sy, sx + hw, sy + hh)
            rf.set(dx, dy, dx + sz, dy + sz)
            c.drawBitmap(pb, rs, rf, tilePaint)
            return
        }
        for (qx in 0..1) for (qy in 0..1) {
            val cb = mem.get(tstyle + "/" + (z + 1) + "/" + (tx * 2 + qx) + "/" + (ty * 2 + qy)) ?: continue
            val h2 = sz / 2f
            rf.set(dx + qx * h2, dy + qy * h2, dx + qx * h2 + h2, dy + qy * h2 + h2)
            c.drawBitmap(cb, null, rf, tilePaint)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        frameGen++
        val nowT = SystemClock.uptimeMillis()
        if (lastDraw != 0L) {
            val gap = nowT - lastDraw
            if (gap < 600L) avgGap = avgGap * 0.9f + gap * 0.1f
        }
        lastDraw = nowT
        fpsN++
        if (fpsT == 0L) fpsT = nowT
        if (nowT - fpsT >= 1000L) {
            fps = fpsN * 1000f / (nowT - fpsT)
            fpsN = 0
            fpsT = nowT
            if (tSpd > 2f && avgGap > tickMs * 2 + 20 && profile < 2) {
                if (++slowCount >= 6) { slowCount = 0; val np = profile + 1; post { onAutoProfile?.invoke(np) } }
            } else if (slowCount > 0) slowCount--
        }
        c.drawColor(if (style == "offd" || style == "offld") Color.rgb(0x14, 0x17, 0x1B) else if (style == "dark" || style == "sat") Color.rgb(0x1B, 0x1B, 0x1B) else if (style == "off" || style == "offl") Color.rgb(0xF3, 0xF0, 0xEA) else Color.rgb(0xE8, 0xE4, 0xDC))
        val z = zoom
        val n = 1 shl z
        val cx = lonToX(centerLon, z)
        val cy = latToY(centerLat, z)
        val rot = if (headingUp && !preview) -heading else 0f
        val tilted = tiltOn()
        val ay = anchorY() * h
        c.save()
        if (tilted) {
            val top = -0.5f * ay
            val k = 0.22f
            val wf = w.toFloat()
            val hf = h.toFloat()
            tm.setPolyToPoly(floatArrayOf(0f, top, wf, top, wf, hf, 0f, hf), 0, floatArrayOf(wf * k, 0f, wf * (1f - k), 0f, wf, hf, 0f, hf), 0, 4)
            c.concat(tm)
        } else {
            tm.reset()
        }
        c.translate(w / 2f, ay)
        c.scale(zf, zf)
        c.rotate(rot)
        val reach = Math.hypot(w / 2.0, Math.max(if (tilted) ay * 1.5 else ay * 1.0, h - ay * 1.0))
        val tsz = ts * zf
        val half = reach / tsz + 1.0
        val x0 = Math.floor(cx - half).toInt()
        val x1 = Math.floor(cx + half).toInt()
        val y0 = Math.max(0, Math.floor(cy - half).toInt())
        val y1 = Math.min(n - 1, Math.floor(cy + half).toInt())
        val gw = x1 - x0 + 1
        val gh = y1 - y0 + 1
        if (grid.size != gw * gh) {
            grid = arrayOfNulls(gw * gh)
            gridZ = -1
        }
        if (gridZ != z || gridStyle != style || gridX0 != x0 || gridY0 != y0) {
            grid.fill(null)
            gridZ = z
            gridStyle = style
            gridX0 = x0
            gridY0 = y0
        }
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                if (Math.hypot((tx + 0.5 - cx) * tsz, (ty + 0.5 - cy) * tsz) > reach + tsz * 0.75) continue
                val txw = ((tx % n) + n) % n
                val gi = (ty - y0) * gw + (tx - x0)
                var bmp = grid[gi]
                if (bmp == null) {
                    bmp = tile(z, txw, ty)
                    grid[gi] = bmp
                }
                val dx = ((tx - cx) * ts).toFloat()
                val dy = ((ty - cy) * ts).toFloat()
                if (bmp != null) {
                    statDrawn++
                    rf.set(dx, dy, dx + ts, dy + ts)
                    c.drawBitmap(bmp, null, rf, tilePaint)
                } else {
                    statMissing++
                    drawFallback(c, z, txw, ty, dx, dy, ts)
                }
            }
        }
        if (prefetchN > 0 && tSpd > 3f && follow && !posLat.isNaN() && frameGen % 6 == 0) prefetchAhead(z, n)
        // route (projection cached per zoom)
        if (route.size > 1) {
            if (rz != z) {
                rx = DoubleArray(route.size)
                ry = DoubleArray(route.size)
                for (i in route.indices) { rx[i] = lonToX(route[i].lon, z); ry[i] = latToY(route[i].lat, z) }
                rz = z
            }
            // Clip the polyline to a box around the screen (in screen space) so nothing is ever
            // drawn beyond the perspective vanishing line or with huge coordinates.
            val path = routePath
            path.reset()
            val th = Math.toRadians(rot.toDouble())
            val ct = Math.cos(th)
            val st = Math.sin(th)
            val bx0 = -w / 2.0 - 60.0
            val bx1 = w / 2.0 + 60.0
            val by0 = -(if (tilted) 1.5 else 1.0) * ay - 60.0
            val by1 = h - ay + 60.0
            var si = 0
            var haveP = false
            var qx0 = 0.0
            var qy0 = 0.0
            if (!posLat.isNaN() && routeStart > 0) {
                si = Math.min(routeStart + 1, rx.size - 1)
                val mx = (lonToX(posLon, z) - cx) * ts
                val my = (latToY(posLat, z) - cy) * ts
                qx0 = (mx * ct - my * st) * zf
                qy0 = (mx * st + my * ct) * zf
                haveP = true
            }
            var drawing = false
            var lastKx = 0.0
            var lastKy = 0.0
            val iEnd = Math.min(rx.size, si + 4000)
            var i = si
            while (i < iEnd) {
                val mx = (rx[i] - cx) * ts
                val my = (ry[i] - cy) * ts
                val qx1 = (mx * ct - my * st) * zf
                val qy1 = (mx * st + my * ct) * zf
                if (!haveP) {
                    qx0 = qx1; qy0 = qy1; haveP = true; lastKx = qx1; lastKy = qy1
                    i++
                    continue
                }
                val last = i == iEnd - 1 || i == rx.size - 1
                if (!last && drawing && Math.abs(qx1 - lastKx) + Math.abs(qy1 - lastKy) < 5.0) { i++; continue }
                ct0 = 0.0; ct1 = 1.0
                val ddx = qx1 - qx0
                val ddy = qy1 - qy0
                if (clipT(-ddx, qx0 - bx0) && clipT(ddx, bx1 - qx0) && clipT(-ddy, qy0 - by0) && clipT(ddy, by1 - qy0)) {
                    val sx = (qx0 + ct0 * ddx).toFloat()
                    val sy = (qy0 + ct0 * ddy).toFloat()
                    if (!drawing || ct0 > 0.0) path.moveTo(sx, sy)
                    path.lineTo((qx0 + ct1 * ddx).toFloat(), (qy0 + ct1 * ddy).toFloat())
                    drawing = ct1 >= 1.0
                } else {
                    drawing = false
                }
                qx0 = qx1; qy0 = qy1
                lastKx = qx1; lastKy = qy1
                i++
            }
            c.save()
            c.rotate(-rot)
            c.scale(1f / zf, 1f / zf)
            line.style = Paint.Style.STROKE
            line.strokeJoin = Paint.Join.ROUND
            line.strokeCap = Paint.Cap.ROUND
            line.color = Color.WHITE
            line.strokeWidth = 22f
            c.drawPath(path, line)
            line.color = Color.rgb(0x2E, 0x8B, 0xFF)
            line.strokeWidth = 15f
            c.drawPath(path, line)
            c.restore()
        }
        // route preview (alternatives): grey ones first, the selected one in blue on top
        if (preview && alts.isNotEmpty()) {
            val order = ArrayList<Int>()
            for (k in alts.indices) if (k != altSel) order.add(k)
            if (altSel in alts.indices) order.add(altSel)
            for (k in order) {
                val pl = alts[k]
                if (pl.size < 2) continue
                val ap = altPath
                ap.reset()
                for (i in pl.indices) {
                    val ax = ((lonToX(pl[i].lon, z) - cx) * ts).toFloat()
                    val ay2 = ((latToY(pl[i].lat, z) - cy) * ts).toFloat()
                    if (i == 0) ap.moveTo(ax, ay2) else ap.lineTo(ax, ay2)
                }
                val sel = k == altSel
                c.save()
                line.style = Paint.Style.STROKE
                line.strokeJoin = Paint.Join.ROUND
                line.strokeCap = Paint.Cap.ROUND
                line.color = Color.WHITE
                line.strokeWidth = (if (sel) 22f else 18f) / zf
                c.drawPath(ap, line)
                line.color = if (sel) Color.rgb(0x2E, 0x8B, 0xFF) else Color.rgb(0x8A, 0x93, 0xA3)
                line.strokeWidth = (if (sel) 15f else 11f) / zf
                c.drawPath(ap, line)
                c.restore()
            }
        }
        // destination
        val d = dest
        if (d != null) {
            val px = ((lonToX(d.lon, z) - cx) * ts).toFloat()
            val py = ((latToY(d.lat, z) - cy) * ts).toFloat()
            val dth = Math.toRadians(rot.toDouble())
            val dqx = (px * Math.cos(dth) - py * Math.sin(dth)) * zf
            val dqy = (px * Math.sin(dth) + py * Math.cos(dth)) * zf
            if (Math.abs(dqx) < w / 2.0 + 60.0 && dqy > -(if (tilted) 1.5 else 1.0) * ay - 60.0 && dqy < h - ay + 60.0) {
            fill.style = Paint.Style.FILL
            fill.color = Color.WHITE
            c.drawCircle(px, py, 20f, fill)
            fill.color = Color.rgb(0xC0, 0x39, 0x2B)
            c.drawCircle(px, py, 13f, fill)
            }
        }
        // position arrow
        if (!posLat.isNaN()) {
            val px = ((lonToX(posLon, z) - cx) * ts).toFloat()
            val py = ((latToY(posLat, z) - cy) * ts).toFloat()
            c.save()
            c.translate(px, py)
            c.rotate(heading)
            val path = arrowPath
            path.reset()
            path.moveTo(0f, -30f)
            path.lineTo(20f, 22f)
            path.lineTo(0f, 11f)
            path.lineTo(-20f, 22f)
            path.close()
            fill.style = Paint.Style.FILL
            fill.color = Color.WHITE
            c.drawCircle(0f, 0f, 27f, fill)
            fill.color = Color.rgb(0x1F, 0x6F, 0xEB)
            c.drawPath(path, fill)
            c.restore()
        }
        c.restore()
        // attribution (required by OpenStreetMap)
        fill.style = Paint.Style.FILL
        fill.color = Color.argb(150, 255, 255, 255)
        fill.textSize = 20f
        val tx = if (style == "sat") "© Esri, Maxar, Earthstar Geographics" else if (style == "off" || style == "offd") "© OpenStreetMap contributors · mapsforge" else "© OpenStreetMap © CARTO"
        val tw = fill.measureText(tx)
        c.drawRect(w - tw - 14f, h - 28f, w.toFloat(), h.toFloat(), fill)
        fill.color = Color.argb(255, 40, 40, 40)
        c.drawText(tx, w - tw - 7f, h - 8f, fill)
    }
}
