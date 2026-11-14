package biz.rakendus.mb

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.File

/**
 * Offline vector map: mapsforge renders tiles from a local .map file on the device.
 * The MapView treats the result like any other raster tile (rotation, route, 3D all keep working).
 */
object OfflineMap {
    @Volatile var ready = false
    @Volatile var err = ""
    private const val TS = 384
    private val lock = Any()
    private var renderer: DatabaseRenderer? = null
    private var store: MapFile? = null
    private var model: DisplayModel? = null
    private var theme: RenderThemeFuture? = null

    fun file(ctx: Context): File = File(File(ctx.filesDir, "maps"), "estonia.map")

    fun available(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && f.length() > 50_000_000L
    }

    /** Heavy: call from a background thread. */
    fun init(ctx: Context): Boolean {
        synchronized(lock) {
            if (ready) return true
            try {
                val f = file(ctx)
                if (!f.exists() || f.length() < 1_000_000L) { err = "kaarti pole"; return false }
                AndroidGraphicFactory.createInstance(ctx.applicationContext as Application)
                DisplayModel.setDeviceScaleFactor(1.5f)
                val dm = DisplayModel()
                dm.setFixedTileSize(TS)
                val mf = MapFile(f)
                val th = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, InternalRenderTheme.DEFAULT, dm)
                val t = Thread(th)
                t.start()
                th.get()
                renderer = DatabaseRenderer(mf, AndroidGraphicFactory.INSTANCE, InMemoryTileCache(8), null, true, false, null)
                store = mf
                model = dm
                theme = th
                ready = true
                err = ""
                return true
            } catch (e: Throwable) {
                err = (e.message ?: e.javaClass.simpleName)
                ready = false
                return false
            }
        }
    }

    /** Renders one XYZ tile to a 384 px RGB_565 bitmap, or null on failure. */
    fun render(z: Int, x: Int, y: Int): Bitmap? {
        synchronized(lock) {
            val r = renderer ?: return null
            val st = store ?: return null
            val dm = model ?: return null
            val th = theme ?: return null
            try {
                val tile = Tile(x, y, z.toByte(), TS)
                val job = RendererJob(tile, st, th, dm, 1.0f, false, false)
                val tb = r.executeJob(job) ?: return null
                val src = AndroidGraphicFactory.getBitmap(tb)
                val out = src.copy(Bitmap.Config.RGB_565, false)
                tb.decrementRefCount()
                return out
            } catch (e: Throwable) {
                err = (e.message ?: e.javaClass.simpleName)
                return null
            }
        }
    }
}
