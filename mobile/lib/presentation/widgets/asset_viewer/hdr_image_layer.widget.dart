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
const _channel = MethodChannel('immich/hdr_image');
const _maxCachedProbes = 200;

final _log = Logger('HdrImageLayer');

// Probing a remote original downloads it, so remember the answer per source.
final _probeCache = <String, Future<bool>>{};

/// Draws the asset natively on Android 14+ when it carries a gain map, so the window can show it in HDR.
///
/// Gestures stay with the [PhotoView] underneath: this layer ignores pointers and mirrors the
/// controller's position and scale onto the native view.
class HdrImageLayer extends StatefulWidget {
  final BaseAsset asset;
  final String? localFilePath;
  final PhotoViewControllerBase? controller;
  final bool enabled;

  const HdrImageLayer({super.key, required this.asset, required this.enabled, this.localFilePath, this.controller});

  @override
  State<HdrImageLayer> createState() => _HdrImageLayerState();
}

class _HdrImageLayerState extends State<HdrImageLayer> {
  Map<String, String>? _source;
  bool _hasGainmap = false;
  MethodChannel? _viewChannel;
  StreamSubscription<PhotoViewControllerValue>? _controllerSub;
  Rect? _sentTarget;
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
    if (oldWidget.controller != widget.controller) {
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
    super.dispose();
  }

  void _listen() {
    unawaited(_controllerSub?.cancel());
    _controllerSub = widget.controller?.outputStateStream.listen((_) => _sendTarget());
  }

  Future<void> _probe() async {
    final source = _sourceFor(widget.asset, widget.localFilePath);
    _source = source;
    if (source == null) {
      return;
    }

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

    final hasGainmap = await probe;
    if (mounted && identical(_source, source) && hasGainmap) {
      setState(() => _hasGainmap = true);
    }
  }

  void _onViewCreated(int id) {
    _viewChannel = MethodChannel('$_viewType/$id');
    _sentTarget = null;
    _sendTarget();
  }

  void _sendTarget() {
    final channel = _viewChannel;
    final controller = widget.controller;
    final boundaries = controller?.scaleBoundaries;
    if (!mounted || channel == null || controller == null || boundaries == null) {
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
        surfaceFactory: (context, controller) => AndroidViewSurface(
          controller: controller as AndroidViewController,
          gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
          hitTestBehavior: PlatformViewHitTestBehavior.transparent,
        ),
        // Hybrid composition keeps the view in the Android hierarchy; a texture-backed view would lose HDR.
        onCreatePlatformView: (params) {
          final controller =
              PlatformViewsService.initExpensiveAndroidView(
                  id: params.id,
                  viewType: _viewType,
                  layoutDirection: TextDirection.ltr,
                  creationParams: source,
                  creationParamsCodec: const StandardMessageCodec(),
                  onFocus: () => params.onFocusChanged(true),
                )
                ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
                ..addOnPlatformViewCreatedListener(_onViewCreated);
          unawaited(controller.create());
          return controller;
        },
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
