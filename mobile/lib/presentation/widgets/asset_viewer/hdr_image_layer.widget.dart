import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:immich_mobile/domain/models/asset/base_asset.model.dart';
import 'package:immich_mobile/infrastructure/repositories/settings.repository.dart';
import 'package:immich_mobile/presentation/widgets/images/image_provider.dart';
import 'package:immich_mobile/utils/image_url_builder.dart';
import 'package:immich_mobile/widgets/photo_view/photo_view.dart';
import 'package:logging/logging.dart';

const _viewType = 'immich/hdr_image_view';
const _keeperViewType = 'immich/hdr_keeper_view';
const _channel = MethodChannel('immich/hdr_image');
const _maxCachedProbes = 200;

final _log = Logger('HdrImageLayer');

// Bumped whenever a native HDR view is created, which switches Flutter to hybrid composition.
final _hdrViewsCreated = ValueNotifier<int>(0);

// Whether Hybrid Composition++ (enabled in AndroidManifest.xml) is available; null until checked.
bool? _hcpp;

Future<bool> _checkHcpp() async {
  return _hcpp ??= await HybridAndroidViewController.checkIfSupported().catchError((Object error) {
    _log.warning('HCPP check failed', error);
    return false;
  });
}

/// Creates the native view with Hybrid Composition++ if available, else with plain hybrid composition.
/// Call [_checkHcpp] first. Either keeps the view in the Android hierarchy; a texture-backed view would lose HDR.
AndroidViewController _createAndroidView(
  PlatformViewCreationParams params,
  String viewType, {
  Map<String, String>? creationParams,
  void Function(int id)? onCreated,
}) {
  final controller = _hcpp == true
      ? PlatformViewsService.initHybridAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
      : PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: creationParams,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        );
  controller.addOnPlatformViewCreatedListener(params.onPlatformViewCreated);
  if (onCreated != null) {
    controller.addOnPlatformViewCreatedListener(onCreated);
  }
  unawaited(controller.create());
  return controller;
}

Widget _androidViewSurface(BuildContext context, PlatformViewController controller) {
  return AndroidViewSurface(
    controller: controller as AndroidViewController,
    gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
    hitTestBehavior: PlatformViewHitTestBehavior.transparent,
  );
}

// Probing a remote original downloads it, so remember the answer per source.
final _probeCache = <String, Future<bool>>{};

/// Starts the gain map check for [asset] ahead of time, e.g. for the next slideshow slide.
void prefetchHdr(BaseAsset asset) {
  final source = _sourceFor(asset, null);
  if (source != null) {
    unawaited(_probeSource(source));
  }
}

Future<bool> _probeSource(Map<String, String> source) {
  final key = source.toString();
  final probe = _probeCache[key] ??= _channel
      .invokeMethod<bool>('probe', source)
      .then((value) => value ?? false)
      .catchError((Object error) {
        _log.warning('HDR probe failed', error);
        unawaited(_probeCache.remove(key));
        return false;
      });
  while (_probeCache.length > _maxCachedProbes) {
    unawaited(_probeCache.remove(_probeCache.keys.first));
  }
  return probe;
}

/// Draws the asset natively on Android 14+ when it carries a gain map, so the window can show it in HDR.
///
/// This layer ignores pointers. With a [controller], it mirrors the [PhotoView] position and scale
/// onto the native view; with [HdrImageLayer.fitted], the native view centers the image itself.
class HdrImageLayer extends StatefulWidget {
  final BaseAsset asset;
  final String? localFilePath;
  final PhotoViewControllerBase? controller;
  final bool enabled;

  /// Fitted mode: cover instead of contain the view.
  final bool cover;

  /// Fitted mode: extra scale around the center, e.g. for the slideshow's Ken Burns effect.
  final Animation<double>? zoom;

  const HdrImageLayer({super.key, required this.asset, required this.enabled, this.localFilePath, this.controller})
    : cover = false,
      zoom = null;

  const HdrImageLayer.fitted({
    super.key,
    required this.asset,
    required this.enabled,
    required this.cover,
    required Animation<double> this.zoom,
  }) : localFilePath = null,
       controller = null;

  @override
  State<HdrImageLayer> createState() => _HdrImageLayerState();
}

class _HdrImageLayerState extends State<HdrImageLayer> {
  Map<String, String>? _source;
  bool _hasGainmap = false;
  MethodChannel? _viewChannel;
  StreamSubscription<PhotoViewControllerValue>? _controllerSub;
  Animation<double>? _listenedZoom;
  Object? _sentTarget;
  double _devicePixelRatio = 1.0;

  @override
  void initState() {
    super.initState();
    _listen();
    if (widget.enabled) {
      unawaited(_probe());
    }
  }

  @override
  void didUpdateWidget(HdrImageLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.asset != widget.asset || oldWidget.localFilePath != widget.localFilePath) {
      _source = null;
      _hasGainmap = false;
      _viewChannel = null;
    }
    if (oldWidget.controller != widget.controller || oldWidget.zoom != widget.zoom) {
      _listen();
      _sentTarget = null;
    }
    if (widget.enabled && _source == null) {
      unawaited(_probe());
    }
  }

  @override
  void dispose() {
    unawaited(_controllerSub?.cancel());
    _listenedZoom?.removeListener(_sendTarget);
    super.dispose();
  }

  void _listen() {
    unawaited(_controllerSub?.cancel());
    _controllerSub = widget.controller?.outputStateStream.listen((_) => _sendTarget());
    _listenedZoom?.removeListener(_sendTarget);
    _listenedZoom = widget.zoom?..addListener(_sendTarget);
  }

  Future<void> _probe() async {
    final source = _sourceFor(widget.asset, widget.localFilePath);
    _source = source;
    if (source == null) {
      return;
    }

    final hasGainmap = await _probeSource(source);
    if (hasGainmap) {
      await _checkHcpp();
    }
    if (mounted && identical(_source, source) && hasGainmap) {
      setState(() => _hasGainmap = true);
    }
  }

  void _onViewCreated(int id) {
    _viewChannel = MethodChannel('$_viewType/$id');
    _sentTarget = null;
    _sendTarget();
    _hdrViewsCreated.value++;
  }

  void _sendTarget() {
    final channel = _viewChannel;
    if (!mounted || channel == null) {
      return;
    }

    final zoom = widget.zoom;
    if (zoom != null) {
      final fit = (widget.cover, zoom.value);
      if (fit != _sentTarget) {
        _sentTarget = fit;
        unawaited(channel.invokeMethod('setFit', {'cover': widget.cover, 'zoom': zoom.value}));
      }
      return;
    }

    final controller = widget.controller;
    final boundaries = controller?.scaleBoundaries;
    if (controller == null || boundaries == null) {
      return;
    }

    // PhotoView centers the child in its viewport, then translates it by the controller position.
    final size = boundaries.childSize * (controller.scale ?? boundaries.initialScale);
    final target = Rect.fromCenter(
      center: boundaries.outerSize.center(controller.position),
      width: size.width,
      height: size.height,
    );
    if (target == _sentTarget) {
      return;
    }
    _sentTarget = target;

    final ratio = _devicePixelRatio;
    unawaited(
      channel.invokeMethod('setTarget', {
        'left': target.left * ratio,
        'top': target.top * ratio,
        'width': target.width * ratio,
        'height': target.height * ratio,
      }),
    );
  }

  @override
  Widget build(BuildContext context) {
    _devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
    final source = _source;
    if (!widget.enabled || !_hasGainmap || source == null) {
      // The native view goes away with the platform view link.
      _viewChannel = null;
      return const SizedBox.shrink();
    }

    // Scale boundaries arrive after the PhotoView lays out, which rebuilds this layer without a controller event.
    WidgetsBinding.instance.addPostFrameCallback((_) => _sendTarget());

    return IgnorePointer(
      child: PlatformViewLink(
        key: ValueKey(source.toString()),
        viewType: _viewType,
        surfaceFactory: _androidViewSurface,
        onCreatePlatformView: (params) =>
            _createAndroidView(params, _viewType, creationParams: source, onCreated: _onViewCreated),
      ),
    );
  }
}

/// Keeps an empty native view on screen once an [HdrImageLayer] has shown one, as long as [enabled].
///
/// With hybrid composition, Flutter draws into an image view while a platform view is on screen and
/// switches back to its surface view in the first frame without one. That surface still holds its
/// last frame from before the switch, so on a page swipe, between the old HDR view going away and the
/// new one being created, an earlier photo flashed up. Hybrid Composition++ doesn't switch surfaces,
/// so the keeper stays inactive there.
class HdrCompositionKeeper extends StatefulWidget {
  final bool enabled;

  const HdrCompositionKeeper({super.key, required this.enabled});

  @override
  State<HdrCompositionKeeper> createState() => _HdrCompositionKeeperState();
}

class _HdrCompositionKeeperState extends State<HdrCompositionKeeper> {
  bool _keep = false;

  @override
  void initState() {
    super.initState();
    _hdrViewsCreated.addListener(_onHdrViewCreated);
  }

  @override
  void didUpdateWidget(HdrCompositionKeeper oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enabled) {
      _keep = false;
    }
  }

  @override
  void dispose() {
    _hdrViewsCreated.removeListener(_onHdrViewCreated);
    super.dispose();
  }

  void _onHdrViewCreated() {
    if (mounted && widget.enabled && !_keep && _hcpp != true) {
      setState(() => _keep = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_keep || !widget.enabled) {
      return const SizedBox.shrink();
    }

    return IgnorePointer(
      child: SizedBox.square(
        dimension: 1,
        child: PlatformViewLink(
          viewType: _keeperViewType,
          surfaceFactory: _androidViewSurface,
          onCreatePlatformView: (params) => _createAndroidView(params, _keeperViewType),
        ),
      ),
    );
  }
}

Map<String, String>? _sourceFor(BaseAsset asset, String? localFilePath) {
  if (!Platform.isAndroid || !asset.isImage) {
    return null;
  }
  if (localFilePath != null) {
    return {'type': 'file', 'path': localFilePath};
  }
  if (shouldUseLocalAsset(asset)) {
    final id = asset is LocalAsset ? asset.id : (asset as RemoteAsset).localId!;
    return {'type': 'local', 'id': id};
  }
  // Server previews drop the gain map; only the original has one.
  if (!SettingsRepository.instance.appConfig.image.loadOriginal) {
    return null;
  }
  final remoteId = asset is RemoteAsset ? asset.id : (asset as LocalAsset).remoteId;
  return remoteId == null ? null : {'type': 'remote', 'url': getOriginalUrlForRemoteId(remoteId)};
}
