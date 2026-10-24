package app.alextran.immich.hdr

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.util.concurrent.Executors

private const val TAG = "HdrImagePlugin"
private const val VIEW_TYPE = "immich/hdr_image_view"

/**
 * `probe` reports whether an asset carries a gain map; the platform view then renders it in HDR.
 * Requires Android 14, the first release that decodes gain maps.
 */
class HdrImagePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
  private lateinit var channel: MethodChannel
  private lateinit var context: Context
  private val executor = Executors.newFixedThreadPool(2)
  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    context = binding.applicationContext
    channel = MethodChannel(binding.binaryMessenger, "immich/hdr_image")
    channel.setMethodCallHandler(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      binding.platformViewRegistry.registerViewFactory(VIEW_TYPE, HdrViewFactory(binding.binaryMessenger))
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    executor.shutdown()
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "probe" -> probe(HdrSource.fromMap(call.arguments as? Map<*, *>), result)
      else -> result.notImplemented()
    }
  }

  private fun probe(source: HdrSource?, result: MethodChannel.Result) {
    if (source == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      result.success(false)
      return
    }
    executor.execute {
      val hasGainmap = hasGainmap(source)
      mainHandler.post { result.success(hasGainmap) }
    }
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  private fun hasGainmap(source: HdrSource): Boolean = try {
    HdrImage.open(context, source).use { it.hasGainmap() }
  } catch (e: Throwable) {
    Log.w(TAG, "Probe failed for $source", e)
    false
  }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class HdrViewFactory(private val messenger: BinaryMessenger) :
  PlatformViewFactory(StandardMessageCodec.INSTANCE) {
  override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
    return HdrPlatformView(context, viewId, messenger, HdrSource.fromMap(args as? Map<*, *>))
  }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class HdrPlatformView(
  context: Context,
  viewId: Int,
  messenger: BinaryMessenger,
  source: HdrSource?,
) : PlatformView, MethodChannel.MethodCallHandler {
  private val view = HdrImageView(context)
  private val channel = MethodChannel(messenger, "$VIEW_TYPE/$viewId")

  init {
    channel.setMethodCallHandler(this)
    source?.let(view::load)
  }

  override fun getView(): View = view

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "setTarget" -> {
        view.setTarget(
          call.argument<Double>("left")!!.toFloat(),
          call.argument<Double>("top")!!.toFloat(),
          call.argument<Double>("width")!!.toFloat(),
          call.argument<Double>("height")!!.toFloat(),
        )
        result.success(null)
      }
      "setFit" -> {
        view.setFit(call.argument<Boolean>("cover")!!, call.argument<Double>("zoom")!!.toFloat())
        result.success(null)
      }
      else -> result.notImplemented()
    }
  }

  override fun dispose() {
    channel.setMethodCallHandler(null)
    view.release()
  }
}
