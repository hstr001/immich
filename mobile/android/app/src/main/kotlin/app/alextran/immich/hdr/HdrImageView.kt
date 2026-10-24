package app.alextran.immich.hdr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val TAG = "HdrImageView"

// Long side of the always-present low-res layer, in decoded pixels.
private const val BASE_MAX_SIZE = 2048

// Tile edge in decoded pixels; a tile covers TILE_SIZE * sampleSize raw image pixels.
private const val TILE_SIZE = 512

/**
 * Draws an image with its gain map so the window can render it in HDR. The layout and gestures stay
 * in Flutter: [setTarget] receives where the oriented image currently sits in view pixels. A
 * downsampled base layer is always drawn; full-resolution tiles are decoded for the visible area
 * once the zoom level needs more detail than the base provides.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HdrImageView(context: Context) : View(context) {
  private data class TileKey(val sample: Int, val col: Int, val row: Int)

  // A single thread keeps decoder access serial and lets close() run after all pending decodes.
  private val executor = Executors.newSingleThreadExecutor()
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

  private var image: HdrImage? = null
  private var base: Bitmap? = null
  private var baseSample = 1
  private val orientation = Matrix()
  private val drawMatrix = Matrix()
  private val inverse = Matrix()
  private val visible = RectF()
  private var target: RectF? = null

  private val tiles = HashMap<TileKey, Bitmap>()
  private val pending = HashSet<TileKey>()

  // Written on the main thread, read by the decode thread to skip tiles that scrolled away.
  @Volatile
  private var wanted: Set<TileKey> = emptySet()

  @Volatile
  private var released = false
  private var holdsHdrMode = false

  fun load(source: HdrSource) {
    executor.execute {
      try {
        val img = HdrImage.open(context, source)
        var sample = 1
        while (max(img.width, img.height) / sample > BASE_MAX_SIZE) sample *= 2
        val bitmap = img.decodeRegion(Rect(0, 0, img.width, img.height), sample)
        if (bitmap == null) {
          img.close()
          return@execute
        }
        if (!bitmap.hasGainmap()) {
          Log.w(TAG, "Region decoder returned no gain map for $source")
        }
        post {
          if (released) {
            // The executor is already shut down; nothing else references img yet.
            img.close()
            return@post
          }
          image = img
          base = bitmap
          baseSample = sample
          orientation.set(img.orientationMatrix())
          invalidate()
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to load $source", e)
      }
    }
  }

  /** Where the oriented image is drawn, in view pixels. */
  fun setTarget(left: Float, top: Float, width: Float, height: Float) {
    target = RectF(left, top, left + width, top + height)
    invalidate()
  }

  fun release() {
    released = true
    wanted = emptySet()
    tiles.clear()
    base = null
    val img = image
    image = null
    executor.execute { img?.close() }
    executor.shutdown()
    setHdrMode(false)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    setHdrMode(true)
  }

  override fun onDetachedFromWindow() {
    setHdrMode(false)
    super.onDetachedFromWindow()
  }

  override fun onDraw(canvas: Canvas) {
    val img = image ?: return
    val base = base ?: return
    val target = target ?: return
    if (target.isEmpty) return

    val scale = target.width() / img.orientedWidth
    drawMatrix.set(orientation)
    drawMatrix.postScale(scale, target.height() / img.orientedHeight)
    drawMatrix.postTranslate(target.left, target.top)

    canvas.save()
    canvas.concat(drawMatrix)
    canvas.drawBitmap(base, null, Rect(0, 0, img.width, img.height), paint)

    // Largest power of two that still gives at least one decoded pixel per screen pixel.
    var sample = 1
    while (sample * 2 * scale <= 1f) sample *= 2

    if (sample < baseSample) {
      drawTiles(canvas, img, sample)
    } else if (tiles.isNotEmpty() || wanted.isNotEmpty()) {
      wanted = emptySet()
      tiles.clear()
    }
    canvas.restore()
  }

  private fun drawTiles(canvas: Canvas, img: HdrImage, sample: Int) {
    drawMatrix.invert(inverse)
    visible.set(0f, 0f, width.toFloat(), height.toFloat())
    inverse.mapRect(visible)
    if (!visible.intersect(0f, 0f, img.width.toFloat(), img.height.toFloat())) {
      wanted = emptySet()
      tiles.clear()
      return
    }

    val span = TILE_SIZE * sample
    val keys = ArrayList<TileKey>()
    for (row in floor(visible.top / span).toInt()..floor((visible.bottom - 1) / span).toInt()) {
      for (col in floor(visible.left / span).toInt()..floor((visible.right - 1) / span).toInt()) {
        keys.add(TileKey(sample, col, row))
      }
    }
    // Publish before requesting, so queued decodes see the current set.
    wanted = keys.toHashSet()
    tiles.keys.retainAll(wanted)

    for (key in keys) {
      val rect = tileRect(img, key)
      val tile = tiles[key]
      if (tile != null) {
        canvas.drawBitmap(tile, null, rect, paint)
      } else {
        request(img, key, rect)
      }
    }
  }

  private fun tileRect(img: HdrImage, key: TileKey): Rect {
    val span = TILE_SIZE * key.sample
    return Rect(
      key.col * span,
      key.row * span,
      min((key.col + 1) * span, img.width),
      min((key.row + 1) * span, img.height),
    )
  }

  private fun request(img: HdrImage, key: TileKey, rect: Rect) {
    if (!pending.add(key)) return
    executor.execute {
      val bitmap = if (released || key !in wanted) null else try {
        img.decodeRegion(rect, key.sample)
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to decode tile $key", e)
        null
      }
      post {
        pending.remove(key)
        if (bitmap != null && !released && key in wanted) {
          tiles[key] = bitmap
          invalidate()
        }
      }
    }
  }

  private fun setHdrMode(enabled: Boolean) {
    if (enabled == holdsHdrMode) return
    val activity = findActivity() ?: return
    holdsHdrMode = enabled
    HdrWindowMode.update(activity, enabled)
  }

  private fun findActivity(): Activity? {
    var ctx = context
    while (ctx is ContextWrapper) {
      if (ctx is Activity) return ctx
      ctx = ctx.baseContext
    }
    return null
  }
}

/** Reference-counted, so overlapping views (e.g. during a page swipe) don't drop HDR mode early. */
private object HdrWindowMode {
  private var holders = 0

  fun update(activity: Activity, enabled: Boolean) {
    holders = max(0, holders + if (enabled) 1 else -1)
    activity.window.colorMode =
      if (holders > 0) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
  }
}
