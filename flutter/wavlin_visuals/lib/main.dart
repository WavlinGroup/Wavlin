// Entrypoint of the embedded Flutter module. The Android app creates one FlutterEngine,
// runs this main(), and drives the visualizer through the channel below.

import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';

import 'eq_graph_params.dart';
import 'eq_response_graph.dart';
import 'reverb_params.dart';
import 'reverb_room_visualizer.dart';
import 'wrapped_background.dart';

const hostChannelName = 'com.wavlin.visuals/reverb';
const wrappedBackgroundChannelName = 'com.wavlin.visuals/wrapped_background';
const eqGraphChannelName = 'com.wavlin.visuals/eq_graph';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _ReverbHostApp());
}

class _ReverbHostApp extends StatefulWidget {
  const _ReverbHostApp();

  @override
  State<_ReverbHostApp> createState() => _ReverbHostAppState();
}

class _ReverbHostAppState extends State<_ReverbHostApp> {
  static const _channel = MethodChannel(hostChannelName);
  ReverbParams _params = ReverbParams.idle;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_onCall);
    // Ask the host to resend its latest state in case it sent it before this handler existed.
    _channel.invokeMethod<void>('ready').catchError((Object _) {});
  }

  @override
  void dispose() {
    _channel.setMethodCallHandler(null);
    super.dispose();
  }

  Future<Object?> _onCall(MethodCall call) async {
    if (call.method != 'update') throw MissingPluginException(call.method);
    final parsed = ReverbParams.tryParse(call.arguments);
    if (parsed == null) throw PlatformException(code: 'bad_args', message: 'invalid reverb params');
    if (mounted) setState(() => _params = parsed);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final p = _params;
    return Directionality(
      textDirection: TextDirection.ltr,
      child: ReverbRoomVisualizer(
        presetIndex: p.presetIndex,
        enabled: p.enabled,
        speed: p.speed,
        primary: p.primary ?? const Color(0xFFFFFFFF),
        secondary: p.secondary ?? const Color(0xFFFFFFFF),
        tertiary: p.tertiary ?? const Color(0xFFFFFFFF),
        muted: p.muted ?? const Color(0xFFFFFFFF),
      ),
    );
  }
}

/// Second entrypoint, run by its own FlutterEngine for the Wrapped pages' background.
@pragma('vm:entry-point')
void wrappedBackgroundMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _WrappedBackgroundHostApp());
}

class _WrappedBackgroundHostApp extends StatefulWidget {
  const _WrappedBackgroundHostApp();

  @override
  State<_WrappedBackgroundHostApp> createState() => _WrappedBackgroundHostAppState();
}

class _WrappedBackgroundHostAppState extends State<_WrappedBackgroundHostApp> {
  static const _channel = MethodChannel(wrappedBackgroundChannelName);
  List<BgElement> _elements = const [];

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_onCall);
    _channel.invokeMethod<void>('ready').catchError((Object _) {});
  }

  @override
  void dispose() {
    _channel.setMethodCallHandler(null);
    super.dispose();
  }

  Future<Object?> _onCall(MethodCall call) async {
    if (call.method != 'update') throw MissingPluginException(call.method);
    final p = WrappedBackgroundParams.tryParse(call.arguments);
    if (p == null) throw PlatformException(code: 'bad_args', message: 'invalid background params');
    final next = generateBgElements(count: p.elementCount, shapes: p.shapes, seed: p.seed);
    if (mounted) setState(() => _elements = next);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.ltr,
      child: WrappedBackground(elements: _elements),
    );
  }
}

/// Third entrypoint, run by its own FlutterEngine for the equalizer's frequency response graph.
@pragma('vm:entry-point')
void eqGraphMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const _EqGraphHostApp());
}

class _EqGraphHostApp extends StatefulWidget {
  const _EqGraphHostApp();

  @override
  State<_EqGraphHostApp> createState() => _EqGraphHostAppState();
}

class _EqGraphHostAppState extends State<_EqGraphHostApp> {
  static const _channel = MethodChannel(eqGraphChannelName);
  EqGraphParams? _params;

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_onCall);
    _channel.invokeMethod<void>('ready').catchError((Object _) {});
  }

  @override
  void dispose() {
    _channel.setMethodCallHandler(null);
    super.dispose();
  }

  Future<Object?> _onCall(MethodCall call) async {
    if (call.method != 'update') throw MissingPluginException(call.method);
    final parsed = EqGraphParams.tryParse(call.arguments);
    if (parsed == null) throw PlatformException(code: 'bad_args', message: 'invalid eq graph params');
    if (mounted) setState(() => _params = parsed);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final p = _params;
    return Directionality(
      textDirection: TextDirection.ltr,
      // Nothing is drawn until the first update, so no empty surface flashes in.
      child: p == null ? const SizedBox.shrink() : EqResponseGraph(params: p),
    );
  }
}
