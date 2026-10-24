package app.alextran.immich.hdr

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore.Images
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import app.alextran.immich.NativeBuffer
import app.alextran.immich.images.ImageFetcherManager
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

sealed class HdrSource {
  data class Local(val id: Long) : HdrSource()
  data class Remote(val url: String) : HdrSource()
  data class Path(val path: String) : HdrSource()

  companion object {
    fun fromMap(args: Map<*, *>?): HdrSource? = when (args?.get("type")) {
      "local" -> (args["id"] as? String)?.toLongOrNull()?.let { Local(it) }
      "remote" -> (args["url"] as? String)?.let { Remote(it) }
      "file" -> (args["path"] as? String)?.let { Path(it) }
      else -> null
    }
  }
}

/**
 * A region-decodable image. Gain maps are decoded alongside each region (API 34+), so every tile
 * keeps its HDR information. [orientation] is the EXIF orientation, which region decoding ignores.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HdrImage private constructor(
  private val decoder: BitmapRegionDecoder,
  val orientation: Int,
  private val pfd: ParcelFileDescriptor?,
) : Closeable {
  val width: Int = decoder.width
  val height: Int = decoder.height

  private val swapsAxes = when (orientation) {
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSVERSE,
    ExifInterface.ORIENTATION_ROTATE_270 -> true
    else -> false
  }
  val orientedWidth = if (swapsAxes) height else width
  val orientedHeight = if (swapsAxes) width else height

  fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return decoder.decodeRegion(rect, options)
  }

  fun hasGainmap(): Boolean {
    var sample = 1
    while (maxOf(width, height) / sample > 64) sample *= 2
    val bitmap = decodeRegion(Rect(0, 0, width, height), sample) ?: return false
    return bitmap.hasGainmap().also { bitmap.recycle() }
  }

  /** Maps raw (decoder) pixel coordinates to oriented image coordinates. */
  fun orientationMatrix(): Matrix {
    val w = width.toFloat()
    val h = height.toFloat()
    // x' = a*x + b*y + tx, y' = c*x + d*y + ty
    val (a, b, tx, c, d, ty) = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Six(-1f, 0f, w, 0f, 1f, 0f)
      ExifInterface.ORIENTATION_ROTATE_180 -> Six(-1f, 0f, w, 0f, -1f, h)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> Six(1f, 0f, 0f, 0f, -1f, h)
      ExifInterface.ORIENTATION_TRANSPOSE -> Six(0f, 1f, 0f, 1f, 0f, 0f)
      ExifInterface.ORIENTATION_ROTATE_90 -> Six(0f, -1f, h, 1f, 0f, 0f)
      ExifInterface.ORIENTATION_TRANSVERSE -> Six(0f, -1f, h, -1f, 0f, w)
      ExifInterface.ORIENTATION_ROTATE_270 -> Six(0f, 1f, 0f, -1f, 0f, w)
      else -> Six(1f, 0f, 0f, 0f, 1f, 0f)
    }
    return Matrix().apply { setValues(floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)) }
  }

  override fun close() {
    decoder.recycle()
    pfd?.close()
  }

  private data class Six(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

  companion object {
    /** Blocking; call off the main thread. Remote sources are downloaded to a small file cache first. */
    fun open(context: Context, source: HdrSource): HdrImage = when (source) {
      is HdrSource.Local -> {
        val resolver = context.contentResolver
        val uri = ContentUris.withAppendedId(Images.Media.EXTERNAL_CONTENT_URI, source.id)
        val orientation = resolver.openInputStream(uri)?.use { readOrientation(ExifInterface(it)) }
          ?: ExifInterface.ORIENTATION_NORMAL
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Could not open ${source.id}")
        try {
          HdrImage(BitmapRegionDecoder.newInstance(pfd), orientation, pfd)
        } catch (e: Throwable) {
          pfd.close()
          throw e
        }
      }
      is HdrSource.Remote -> openFile(HdrFileCache.get(context, source.url))
      is HdrSource.Path -> openFile(File(source.path))
    }

    private fun openFile(file: File): HdrImage {
      val orientation = readOrientation(ExifInterface(file))
      return HdrImage(BitmapRegionDecoder.newInstance(file.path), orientation, null)
    }

    private fun readOrientation(exif: ExifInterface) =
      exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
  }
}

/** Keeps the last few downloaded originals on disk so the probe and the view share one download. */
private object HdrFileCache {
  private const val MAX_FILES = 4
  private val locks = ConcurrentHashMap<String, Any>()

  fun get(context: Context, url: String): File {
    val dir = File(context.cacheDir, "hdr_originals").apply { mkdirs() }
    val file = File(dir, sha1(url))
    synchronized(locks.computeIfAbsent(url) { Any() }) {
      if (file.exists()) {
        file.setLastModified(System.currentTimeMillis())
        return file
      }
      download(url, file)
    }
    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    return file
  }

  private fun download(url: String, target: File) {
    val latch = CountDownLatch(1)
    var error: Exception? = null
    val tmp = File(target.parentFile, "${target.name}.tmp")
    ImageFetcherManager.fetch(
      url,
      CancellationSignal(),
      onSuccess = { buffer, _ ->
        try {
          FileOutputStream(tmp).channel.use { it.write(NativeBuffer.wrap(buffer.pointer, buffer.offset)) }
          if (!tmp.renameTo(target)) throw IOException("Could not move $tmp")
        } catch (e: Exception) {
          error = e
        } finally {
          buffer.free()
          latch.countDown()
        }
      },
      onFailure = { e ->
        error = e
        latch.countDown()
      },
    )
    latch.await()
    error?.let {
      tmp.delete()
      throw it
    }
  }

  private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
