// Animated visualizer for the Reverb page of the player menu, ported from ReverbRoomVisualizer.kt.
//
// Every preset shows one impulse and what the space does to it: a short ping-pong, a long
// swelling tail, far-away echoes, a vibrating metal sheet and a travelling coil wave.

import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show Ticker;

import 'reverb_math.dart';

class ReverbRoomVisualizer extends StatefulWidget {
  const ReverbRoomVisualizer({
    super.key,
    required this.presetIndex,
    required this.enabled,
    this.speed = 1.0,
    this.primary,
    this.secondary,
    this.tertiary,
    this.muted,
  });

  final int presetIndex;
  final bool enabled;
  final double speed;

  /// Colors default to the ambient Material 3 color scheme.
  final Color? primary;
  final Color? secondary;
  final Color? tertiary;
  final Color? muted;

  @override
  State<ReverbRoomVisualizer> createState() => _ReverbRoomVisualizerState();
}

class _ReverbRoomVisualizerState extends State<ReverbRoomVisualizer> with TickerProviderStateMixin {
  static const _presets = [presetRoom, presetHall, presetStadium, presetPlate, presetSpring];

  late final Ticker _ticker;
  late final List<AnimationController> _weights;
  final ValueNotifier<double> _time = ValueNotifier<double>(0.0);
  Duration _last = Duration.zero;

  @override
  void initState() {
    super.initState();
    _weights = [
      for (final p in _presets)
        AnimationController(
          vsync: this,
          duration: const Duration(milliseconds: 450),
          value: _isActive(p) ? 1.0 : 0.0,
        ),
    ];
    _ticker = createTicker(_onTick);
    _syncTicker();
  }

  bool _isActive(int preset) => widget.enabled && widget.presetIndex == preset;

  void _onTick(Duration elapsed) {
    final dt = (elapsed - _last).inMicroseconds / 1e6;
    _last = elapsed;
    _time.value += dt * widget.speed.clamp(0.25, 3.0).toDouble();
  }

  void _syncTicker() {
    if (widget.enabled) {
      if (!_ticker.isActive) {
        _last = Duration.zero;
        _ticker.start();
      }
    } else {
      if (_ticker.isActive) _ticker.stop();
      _time.value = 0.0;
    }
  }

  @override
  void didUpdateWidget(ReverbRoomVisualizer oldWidget) {
    super.didUpdateWidget(oldWidget);
    for (var i = 0; i < _presets.length; i++) {
      final controller = _weights[i];
      if (_isActive(_presets[i])) {
        if (controller.status != AnimationStatus.forward && controller.value < 1.0) controller.forward();
      } else {
        if (controller.status != AnimationStatus.reverse && controller.value > 0.0) controller.reverse();
      }
    }
    _syncTicker();
  }

  @override
  void dispose() {
    _ticker.dispose();
    for (final c in _weights) {
      c.dispose();
    }
    _time.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return RepaintBoundary(
      child: SizedBox(
        width: double.infinity,
        height: 130,
        child: CustomPaint(
          painter: _ReverbPainter(
            time: _time,
            weights: _weights,
            primary: widget.primary ?? scheme.primary,
            secondary: widget.secondary ?? scheme.secondary,
            tertiary: widget.tertiary ?? scheme.tertiary,
            muted: widget.muted ?? scheme.onSurfaceVariant,
          ),
        ),
      ),
    );
  }
}

class _ReverbPainter extends CustomPainter {
  _ReverbPainter({
    required this.time,
    required this.weights,
    required this.primary,
    required this.secondary,
    required this.tertiary,
    required this.muted,
  }) : super(repaint: Listenable.merge([time, ...weights]));

  final ValueNotifier<double> time;
  final List<AnimationController> weights;
  final Color primary;
  final Color secondary;
  final Color tertiary;
  final Color muted;

  double _w(int i) => Curves.fastOutSlowIn.transform(weights[i].value.clamp(0.0, 1.0).toDouble());

  static Color _a(Color c, double alpha) => c.withValues(alpha: clamp01(alpha));

  static Paint _stroke(Color c, double width, {StrokeCap cap = StrokeCap.butt}) => Paint()
    ..color = c
    ..style = PaintingStyle.stroke
    ..strokeWidth = width
    ..strokeCap = cap;

  static Paint _fill(Color c) => Paint()
    ..color = c
    ..style = PaintingStyle.fill;

  @override
  void paint(Canvas canvas, Size size) {
    final t = time.value;
    final center = size.center(Offset.zero);
    final roomW = _w(0), hallW = _w(1), stadiumW = _w(2), plateW = _w(3), springW = _w(4);
    final total = clamp01(roomW + hallW + stadiumW + plateW + springW);

    if (total > 0.0) {
      final radius = size.height * 0.55;
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..shader = ui.Gradient.radial(
            center,
            radius,
            [_a(primary, 0.16 * total), const Color(0x00000000)],
          ),
      );
    }
    if (total < 1.0) _drawIdle(canvas, size, center, t, 1.0 - total);
    if (roomW > 0.001) _drawPingPong(canvas, size, center, t, roomW);
    if (hallW > 0.001) _drawSwell(canvas, size, center, t, hallW);
    if (stadiumW > 0.001) _drawEchoes(canvas, size, center, t, stadiumW);
    if (plateW > 0.001) _drawPlate(canvas, size, center, t, plateW);
    if (springW > 0.001) _drawCoil(canvas, size, center, t, springW);
  }

  /// Draws [path] as dashes, replacing Compose's PathEffect.dashPathEffect.
  void _dashed(Canvas canvas, Path path, Paint paint, double dash, double gap) {
    for (final metric in path.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        canvas.drawPath(metric.extractPath(d, math.min(d + dash, metric.length)), paint);
        d += dash + gap;
      }
    }
  }

  void _drawIdle(Canvas canvas, Size size, Offset center, double t, double weight) {
    final half = size.width * 0.3;
    final line = Path()
      ..moveTo(center.dx - half, center.dy)
      ..lineTo(center.dx + half, center.dy);
    _dashed(canvas, line, _stroke(_a(muted, 0.3 * weight), 1.5, cap: StrokeCap.round), 6, 8);
    final breath = 0.5 + 0.5 * math.sin(t * 1.5);
    canvas.drawCircle(center, 3, _fill(_a(muted, (0.35 + 0.3 * breath) * weight)));
  }

  void _drawPingPong(Canvas canvas, Size size, Offset center, double t, double weight) {
    final phase = pulsePhase(t);
    final reach = size.width * 0.17;
    const gap = 12.0;
    final wallHalfHeight = size.height * 0.24;
    double xAt(double p) => center.dx + reach * pingPongSwing(p);

    final flash = pingPongFlash(phase);
    final wallRight = center.dx + reach + gap;
    final wallLeft = center.dx - reach - gap;
    canvas.drawLine(
      Offset(wallRight, center.dy - wallHalfHeight),
      Offset(wallRight, center.dy + wallHalfHeight),
      _stroke(_a(secondary, (0.35 + 0.65 * flash.right) * weight), 3, cap: StrokeCap.round),
    );
    canvas.drawLine(
      Offset(wallLeft, center.dy - wallHalfHeight),
      Offset(wallLeft, center.dy + wallHalfHeight),
      _stroke(_a(secondary, (0.35 + 0.65 * flash.left) * weight), 3, cap: StrokeCap.round),
    );
    canvas.drawLine(
      Offset(wallLeft, center.dy),
      Offset(wallRight, center.dy),
      _stroke(_a(primary, 0.18 * weight), 1),
    );

    for (var k = 7; k >= 1; k--) {
      final p = phase - k * 0.011;
      if (p < 0.0) continue;
      final a = clamp01(0.5 * (1.0 - k / 8.0));
      canvas.drawCircle(Offset(xAt(p), center.dy), math.max(1.0, 5 - k * 0.4), _fill(_a(primary, a * weight)));
    }
    final dot = Offset(xAt(phase), center.dy);
    canvas.drawCircle(dot, 10, _fill(_a(primary, 0.22 * weight)));
    canvas.drawCircle(dot, 5, _fill(_a(primary, weight)));
  }

  void _drawSwell(Canvas canvas, Size size, Offset center, double t, double weight) {
    final phase = pulsePhase(t);
    const bars = 29;
    final span = size.width * 0.8;
    final left = center.dx - span / 2.0;
    final maxHalf = size.height * 0.44;

    for (var i = 0; i < bars; i++) {
      final bar = swellBar(i, t, phase, bars: bars);
      final half = (2.0 + maxHalf * bar.a) * weight;
      final x = left + span * (i / (bars - 1.0));
      final color = Color.lerp(primary, secondary, bar.d)!;
      canvas.drawLine(
        Offset(x, center.dy - half),
        Offset(x, center.dy + half),
        _stroke(_a(color, (0.3 + 0.7 * bar.a) * weight), (span / bars) * 0.46, cap: StrokeCap.round),
      );
    }
  }

  void _drawEchoes(Canvas canvas, Size size, Offset center, double t, double weight) {
    final phase = pulsePhase(t);
    final rx = size.width * 0.46;
    final ry = size.height * 0.42;

    var firstRadius = 0.0;
    for (var k = 0; k <= 3; k++) {
      final ring = echoRing(k, phase);
      if (ring == null) continue;
      if (k == 0) firstRadius = ring.r;
      canvas.drawOval(
        Rect.fromLTWH(center.dx - rx * ring.r, center.dy - ry * ring.r, rx * ring.r * 2, ry * ring.r * 2),
        _stroke(_a(secondary, ring.alpha * weight), 2.4 - k * 0.45),
      );
    }

    final flash = clamp01((firstRadius - 0.85) / 0.15);
    final outer = Path()..addOval(Rect.fromLTWH(center.dx - rx, center.dy - ry, rx * 2, ry * 2));
    _dashed(canvas, outer, _stroke(_a(secondary, (0.14 + 0.6 * flash) * weight), 1.5), 4, 7);
    final pulse = 1.0 - phase;
    canvas.drawCircle(center, 3.5 + 3.0 * pulse, _fill(_a(secondary, weight)));
  }

  void _drawPlate(Canvas canvas, Size size, Offset center, double t, double weight) {
    const cols = 19;
    const rows = 7;
    final areaW = size.width * 0.82;
    final areaH = size.height * 0.64;
    final left = center.dx - areaW / 2.0;
    final top = center.dy - areaH / 2.0;
    final c1 = math.cos(t * 2.2);
    final c2 = math.cos(t * 3.1 + 1.0);

    for (var j = 0; j < rows; j++) {
      for (var i = 0; i < cols; i++) {
        final x = i / (cols - 1.0);
        final y = j / (rows - 1.0);
        final m = plateMagnitude(x, y, c1, c2);
        canvas.drawCircle(
          Offset(left + areaW * x, top + areaH * y),
          (1.3 + 3.4 * m) * weight,
          _fill(_a(tertiary, (0.25 + 0.75 * m) * weight)),
        );
      }
    }
  }

  void _drawCoil(Canvas canvas, Size size, Offset center, double t, double weight) {
    final len = size.width * 0.72;
    final left = center.dx - len / 2.0;
    final swing = size.width * 0.04;
    final radius = size.height * 0.3 * weight;
    const steps = 240;
    final path = Path();

    for (var i = 0; i <= steps; i++) {
      final p = coilParams(i, steps, t);
      final x = left + len * p.u + swing * math.cos(p.angle);
      final y = center.dy + radius * (1.0 + 0.12 * p.wave) * math.sin(p.angle);
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    // Compose applied `alpha = weight` to the brush; here it is folded into the gradient colors.
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..shader = ui.Gradient.linear(
        Offset(left, 0),
        Offset(left + len, 0),
        [_a(primary, primary.a * weight), _a(tertiary, tertiary.a * weight)],
      );
    canvas.drawPath(path, paint);

    final tip = Offset(left + len + swing, center.dy);
    canvas.drawCircle(tip, 9, _fill(_a(tertiary, 0.25 * weight)));
    canvas.drawCircle(tip, 4.5, _fill(_a(tertiary, weight)));
  }

  @override
  bool shouldRepaint(_ReverbPainter oldDelegate) =>
      oldDelegate.primary != primary ||
      oldDelegate.secondary != secondary ||
      oldDelegate.tertiary != tertiary ||
      oldDelegate.muted != muted;
}
