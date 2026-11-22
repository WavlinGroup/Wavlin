// Drifting shapes behind the Wrapped pages, ported from AnimatedBackground.kt.
// Generation and motion are pure functions so they can be tested without a screen.

import 'dart:math' as math;

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/widgets.dart';
import 'package:flutter/scheduler.dart' show Ticker;

enum BgShape { circle, rect, line }

BgShape? bgShapeFromName(Object? name) => switch (name) {
      'circle' => BgShape.circle,
      'rect' => BgShape.rect,
      'line' => BgShape.line,
      _ => null,
    };

class BgElement {
  const BgElement({
    required this.shape,
    required this.initialX,
    required this.initialY,
    required this.targetX,
    required this.targetY,
    required this.size,
    required this.alpha,
    required this.durationMs,
  });

  final BgShape shape;

  /// Positions are fractions of the canvas, 0..1.
  final double initialX;
  final double initialY;
  final double targetX;
  final double targetY;

  /// In Android pixels, like the Kotlin version: radius for circles, side for rects.
  final double size;
  final double alpha;
  final int durationMs;
}

/// Same ranges as the Kotlin generator. The random sequence differs from Kotlin's, so the
/// layout for a given seed is not identical, only statistically the same.
List<BgElement> generateBgElements({
  required int count,
  required List<BgShape> shapes,
  required int seed,
}) {
  if (count <= 0 || shapes.isEmpty) return const [];
  final random = math.Random(seed);
  final out = <BgElement>[];
  for (var i = 0; i < count; i++) {
    final shape = shapes[random.nextInt(shapes.length)];
    final initialX = random.nextDouble();
    final initialY = random.nextDouble();
    final targetX = random.nextDouble();
    final targetY = random.nextDouble();
    final size = shape == BgShape.circle ? random.nextDouble() * 15 + 5 : random.nextDouble() * 50 + 10;
    final alpha = random.nextDouble() * 0.3 + 0.1;
    final durationMs = 4000 + random.nextInt(6000);
    out.add(BgElement(
      shape: shape,
      initialX: initialX,
      initialY: initialY,
      targetX: targetX,
      targetY: targetY,
      size: size,
      alpha: alpha,
      durationMs: durationMs,
    ));
  }
  return out;
}

/// Linear 0 -> 1 -> 0 repeat, like RepeatMode.Reverse with LinearEasing.
double reverseProgress(int elapsedMs, int durationMs) {
  if (durationMs <= 0 || elapsedMs < 0) return 0;
  final m = elapsedMs % (2 * durationMs);
  return m <= durationMs ? m / durationMs : (2 * durationMs - m) / durationMs;
}

(double, double) positionAt(BgElement e, double progress) => (
      e.initialX + (e.targetX - e.initialX) * progress,
      e.initialY + (e.targetY - e.initialY) * progress,
    );

class WrappedBackgroundParams {
  const WrappedBackgroundParams({required this.elementCount, required this.shapes, required this.seed});

  static const maxElements = 200;
  static const idle = WrappedBackgroundParams(elementCount: 0, shapes: [BgShape.circle], seed: 0);

  final int elementCount;
  final List<BgShape> shapes;
  final int seed;

  static WrappedBackgroundParams? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final count = raw['elementCount'];
    final shapes = raw['shapes'];
    final seed = raw['seed'];
    if (count is! int || shapes is! List || seed is! int) return null;
    final parsed = <BgShape>[];
    for (final s in shapes) {
      final shape = bgShapeFromName(s);
      if (shape == null) return null;
      parsed.add(shape);
    }
    if (parsed.isEmpty) return null;
    return WrappedBackgroundParams(elementCount: count.clamp(0, maxElements), shapes: parsed, seed: seed);
  }
}

class WrappedBackground extends StatefulWidget {
  const WrappedBackground({super.key, required this.elements});

  final List<BgElement> elements;

  @override
  State<WrappedBackground> createState() => _WrappedBackgroundState();
}

class _WrappedBackgroundState extends State<WrappedBackground> with SingleTickerProviderStateMixin {
  final ValueNotifier<int> _elapsedMs = ValueNotifier<int>(0);
  late final Ticker _ticker;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker((d) => _elapsedMs.value = d.inMilliseconds)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _elapsedMs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dpr = View.of(context).devicePixelRatio;
    return RepaintBoundary(
      child: SizedBox.expand(
        child: CustomPaint(
          painter: _BackgroundPainter(
            elements: widget.elements,
            elapsedMs: _elapsedMs,
            pixelScale: dpr > 0 ? 1 / dpr : 1,
          ),
        ),
      ),
    );
  }
}

class _BackgroundPainter extends CustomPainter {
  _BackgroundPainter({required this.elements, required this.elapsedMs, required this.pixelScale})
      : super(repaint: elapsedMs);

  final List<BgElement> elements;
  final ValueListenable<int> elapsedMs;

  /// Logical pixels per Android pixel, so sizes match the Kotlin version on screen.
  final double pixelScale;

  @override
  void paint(Canvas canvas, Size size) {
    final t = elapsedMs.value;
    final paint = Paint()..strokeWidth = 2 * pixelScale;
    for (final e in elements) {
      final p = reverseProgress(t, e.durationMs);
      final (x, y) = positionAt(e, p);
      paint.color = Color.fromRGBO(255, 255, 255, e.alpha);
      final origin = Offset(x * size.width, y * size.height);
      switch (e.shape) {
        case BgShape.circle:
          canvas.drawCircle(origin, e.size * pixelScale, paint..style = PaintingStyle.fill);
        case BgShape.rect:
          final side = e.size * pixelScale;
          canvas.drawRect(origin & Size(side, side), paint..style = PaintingStyle.fill);
        case BgShape.line:
          final endX = x + (e.targetX - e.initialX) * 0.1;
          final endY = y + (e.targetY - e.initialY) * 0.1;
          canvas.drawLine(origin, Offset(endX * size.width, endY * size.height), paint..style = PaintingStyle.stroke);
      }
    }
  }

  @override
  bool shouldRepaint(_BackgroundPainter old) =>
      !identical(old.elements, elements) || old.pixelScale != pixelScale;
}
