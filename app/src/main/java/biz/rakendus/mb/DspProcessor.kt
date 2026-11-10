package biz.rakendus.mb

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Immutable DSP settings, parsed from the same JSON the web player saves on the server. */
class DspCfg(
    val on: Boolean,
    val normDb: Double,
    val intensity: Double,
    val preDb: Double,
    val width: Double,
    val sat: Int,
    val hp: Double,
    val lp: Double,
    val subDb: Double,
    val enh: Double,
    val ecf: Double,
    val eqF: DoubleArray,
    val eqG: DoubleArray,
    val eqQ: DoubleArray,
    val cm: Int,
    val thr: Double,
    val rat: Double,
    val att: Double,
    val rel: Double,
    val knee: Double,
    val mk: Double,
    val mka: Boolean,
    val lc: Double,
    val lr: Double
) {
    companion object {
        val EQF = doubleArrayOf(40.0, 60.0, 90.0, 135.0, 200.0, 300.0, 450.0, 700.0, 1000.0, 1500.0, 2200.0, 3300.0, 5000.0, 7500.0, 10000.0, 13000.0)

        fun from(d: JSONObject?, on: Boolean, normDb: Double): DspCfg {
            val j = d ?: JSONObject()
            val n = EQF.size
            val f = DoubleArray(n) { EQF[it] }
            val g = DoubleArray(n)
            val q = DoubleArray(n) { if (it == 0 || it == n - 1) 0.7 else 1.2 }
            val arr = j.optJSONArray("eq")
            if (arr != null && arr.length() == n) {
                for (i in 0 until n) {
                    val e = arr.optJSONObject(i) ?: continue
                    f[i] = e.optDouble("f", f[i])
                    g[i] = e.optDouble("g", 0.0)
                    q[i] = e.optDouble("q", q[i])
                }
            }
            return DspCfg(
                on, normDb,
                j.optDouble("int", 100.0) / 100.0,
                j.optDouble("pre", 0.0),
                j.optDouble("wid", 100.0),
                j.optInt("sat", 0),
                j.optDouble("hp", 24.0),
                j.optDouble("lp", 20000.0),
                j.optDouble("sub", 0.0),
                j.optDouble("enh", 0.0),
                j.optDouble("ecf", 110.0),
                f, g, q,
                j.optInt("cm", 0),
                j.optDouble("thr", -24.0),
                j.optDouble("rat", 3.0),
                j.optDouble("att", 20.0),
                j.optDouble("rel", 220.0),
                j.optDouble("knee", 12.0),
                j.optDouble("mk", 0.0),
                j.optBoolean("mka", true),
                j.optDouble("lc", -1.0),
                j.optDouble("lr", 120.0)
            )
        }
    }
}

/** One RBJ biquad section with per-channel state (max 2 channels). */
class Biquad {
    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
    private val z1 = DoubleArray(2)
    private val z2 = DoubleArray(2)

    fun reset() { z1[0] = 0.0; z1[1] = 0.0; z2[0] = 0.0; z2[1] = 0.0 }

    private fun norm(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0; a1 = na1 / a0; a2 = na2 / a0
    }

    fun peaking(f: Double, gDb: Double, q: Double, fs: Double) {
        val a = 10.0.pow(gDb / 40.0)
        val w = 2.0 * Math.PI * f / fs
        val c = cos(w); val al = sin(w) / (2.0 * q)
        norm(1.0 + al * a, -2.0 * c, 1.0 - al * a, 1.0 + al / a, -2.0 * c, 1.0 - al / a)
    }

    fun lowShelf(f: Double, gDb: Double, q: Double, fs: Double) {
        val a = 10.0.pow(gDb / 40.0)
        val w = 2.0 * Math.PI * f / fs
        val c = cos(w); val al = sin(w) / (2.0 * q); val sa = 2.0 * sqrt(a) * al
        norm(
            a * ((a + 1) - (a - 1) * c + sa),
            2.0 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2.0 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa
        )
    }

    fun highShelf(f: Double, gDb: Double, q: Double, fs: Double) {
        val a = 10.0.pow(gDb / 40.0)
        val w = 2.0 * Math.PI * f / fs
        val c = cos(w); val al = sin(w) / (2.0 * q); val sa = 2.0 * sqrt(a) * al
        norm(
            a * ((a + 1) + (a - 1) * c + sa),
            -2.0 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa,
            2.0 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - sa
        )
    }

    fun lowPass(f: Double, q: Double, fs: Double) {
        val w = 2.0 * Math.PI * f / fs
        val c = cos(w); val al = sin(w) / (2.0 * q)
        norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2.0 * c, 1 - al)
    }

    fun highPass(f: Double, q: Double, fs: Double) {
        val w = 2.0 * Math.PI * f / fs
        val c = cos(w); val al = sin(w) / (2.0 * q)
        norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2.0 * c, 1 - al)
    }

    fun run(ch: Int, x: Double): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }
}

/**
 * Media3 audio processor: loudness gain, subsonic high-pass, 16-band EQ, low-pass, sub boost,
 * bass harmonics, stereo width, compressor, soft saturation, limiter and hard ceiling.
 * Works on 16-bit PCM, mono or stereo. Anything else is passed through untouched.
 */
class DspProcessor : BaseAudioProcessor() {
    @Volatile private var pending: DspCfg? = null
    private var cfg: DspCfg? = null
    private var fs = 44100.0
    private var ch = 2

    // filter chain (rebuilt when settings change)
    private var hpA = Biquad(); private var hpB = Biquad()
    private var eq: Array<Biquad> = emptyArray()
    private var lpF: Biquad? = null
    private var subF: Biquad? = null
    private var eLP = Biquad(); private var eHP = Biquad(); private var eLP2 = Biquad()
    private var useEnh = false; private var enhAmt = 0.0
    private var useHp = true
    private var gain = 1.0
    private var width = 1.0
    private var comp = false
    private var cThr = -24.0; private var cRat = 3.0; private var cKnee = 12.0
    private var cAtt = 0.0; private var cRel = 0.0; private var cMk = 1.0
    private var satA = 0.0
    private var limThr = 0.89; private var limRel = 0.0; private var ceil = 0.89
    private var env = 0.0
    private var lim = 1.0

    fun update(c: DspCfg) { pending = c }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        fs = inputAudioFormat.sampleRate.toDouble()
        ch = inputAudioFormat.channelCount
        cfg = null
        return inputAudioFormat
    }

    override fun onFlush() {
        env = 0.0; lim = 1.0
        hpA.reset(); hpB.reset(); for (b in eq) b.reset()
        lpF?.reset(); subF?.reset(); eLP.reset(); eHP.reset(); eLP2.reset()
    }

    private fun rebuild(c: DspCfg) {
        cfg = c
        val nyq = fs / 2.0 - 100.0
        gain = 10.0.pow((c.normDb + c.preDb) / 20.0)
        useHp = c.hp > 5.0
        hpA = Biquad(); hpB = Biquad()
        if (useHp) { hpA.highPass(min(c.hp, nyq), 0.7071, fs); hpB.highPass(min(c.hp, nyq), 0.7071, fs) }
        val list = ArrayList<Biquad>()
        val n = c.eqF.size
        for (i in 0 until n) {
            val g = c.eqG[i] * c.intensity
            if (abs(g) < 0.05) continue
            val b = Biquad()
            val f = min(c.eqF[i], nyq)
            if (i == 0) b.lowShelf(f, g, c.eqQ[i], fs)
            else if (i == n - 1) b.highShelf(f, g, c.eqQ[i], fs)
            else b.peaking(f, g, c.eqQ[i], fs)
            list.add(b)
        }
        eq = list.toTypedArray()
        if (c.lp < 19900.0) { val b = Biquad(); b.lowPass(min(c.lp, nyq), 0.7071, fs); lpF = b } else lpF = null
        val sdb = c.subDb * c.intensity
        if (abs(sdb) > 0.05) { val b = Biquad(); b.peaking(45.0, sdb, 1.0, fs); subF = b } else subF = null
        enhAmt = c.enh / 100.0 * 0.7 * c.intensity
        useEnh = enhAmt > 0.001
        eLP = Biquad(); eHP = Biquad(); eLP2 = Biquad()
        if (useEnh) {
            eLP.lowPass(min(c.ecf, nyq), 0.7071, fs)
            eHP.highPass(min(c.ecf * 1.1, nyq), 0.7071, fs)
            eLP2.lowPass(min(c.ecf * 4.0, nyq), 0.7071, fs)
        }
        width = c.width / 100.0
        // Compressor (multiband from the web version is approximated by one band here)
        comp = c.cm != 0
        var ratio = 1.0 + (c.rat - 1.0) * c.intensity
        var thr = c.thr
        if (c.cm == 2) { ratio = 1.0 + 2.2 * c.intensity; thr = c.thr + 0.0 }
        cThr = thr; cRat = max(1.0, ratio); cKnee = c.knee
        cAtt = exp(-1.0 / (max(0.1, c.att) / 1000.0 * fs))
        cRel = exp(-1.0 / (max(5.0, c.rel) / 1000.0 * fs))
        var mkDb = 0.0
        if (comp) mkDb = if (c.mka) min(12.0, max(0.0, -thr * (1.0 - 1.0 / cRat) * 0.5)) else c.mk
        cMk = 10.0.pow(mkDb / 20.0)
        satA = when (c.sat) { 1 -> 0.62; 2 -> 0.4; else -> 0.0 }
        limThr = 10.0.pow((c.lc - 1.0) / 20.0)
        ceil = 10.0.pow(c.lc / 20.0)
        limRel = exp(-1.0 / (max(10.0, c.lr) / 1000.0 * fs))
        onFlush()
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val p = pending
        if (p != null && p !== cfg) rebuild(p)
        val out: ByteBuffer = replaceOutputBuffer(size)
        val c = cfg
        if (c == null || !c.on) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val chn = ch
        val frames = size / (2 * chn)
        val l = DoubleArray(2)
        for (n in 0 until frames) {
            for (k in 0 until chn) {
                var x = inputBuffer.getShort() / 32768.0 * gain
                if (useHp) x = hpB.run(k, hpA.run(k, x))
                for (b in eq) x = b.run(k, x)
                val lf = lpF
                if (lf != null) x = lf.run(k, x)
                val sf = subF
                if (sf != null) x = sf.run(k, x)
                if (useEnh) {
                    var h = eLP.run(k, x)
                    h = tanh(h * 3.0)
                    h = eLP2.run(k, eHP.run(k, h))
                    x += h * enhAmt
                }
                l[k] = x
            }
            if (chn == 2 && abs(width - 1.0) > 0.01) {
                val m = (l[0] + l[1]) * 0.5
                val s = (l[0] - l[1]) * 0.5 * width
                l[0] = m + s; l[1] = m - s
            }
            if (comp) {
                var pk = abs(l[0]); if (chn == 2) pk = max(pk, abs(l[1]))
                val db = 20.0 * ln(pk + 1e-9) / LN10
                val over = db - cThr
                var gr = 0.0
                if (2.0 * over < -cKnee) gr = 0.0
                else if (2.0 * abs(over) <= cKnee) { val t = over + cKnee / 2.0; gr = (1.0 / cRat - 1.0) * t * t / (2.0 * cKnee) }
                else if (over > 0) gr = (1.0 / cRat - 1.0) * over
                env = if (gr < env) cAtt * env + (1.0 - cAtt) * gr else cRel * env + (1.0 - cRel) * gr
                val g = 10.0.pow((env) / 20.0) * cMk
                l[0] *= g; if (chn == 2) l[1] *= g
            }
            for (k in 0 until chn) {
                var x = l[k]
                if (satA > 0.0) x = satA * tanh(x / satA)
                l[k] = x
            }
            var pk2 = abs(l[0]); if (chn == 2) pk2 = max(pk2, abs(l[1]))
            val need = if (pk2 > limThr) limThr / pk2 else 1.0
            lim = if (need < lim) need else min(need, 1.0 - (1.0 - lim) * limRel)
            for (k in 0 until chn) {
                var x = l[k] * lim
                if (x > ceil) x = ceil else if (x < -ceil) x = -ceil
                var v = Math.round(x * 32767.0).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                out.putShort(v.toShort())
            }
        }
        out.flip()
    }

    companion object { private val LN10 = ln(10.0) }
}
