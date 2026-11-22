// Frequency response graph of the equalizer, ported from EqFrequencyResponseGraph.kt.
//
// The response curve itself is computed by the Android host (the same code also sizes the
// anti-clipping preamp) and arrives as points; this file only draws it.

import 'package:flutter/widgets.dart';

import 'eq_graph_math.dart';
import 'eq_graph_params.dart';

class EqResponseGraph extends StatelessWidget {
  const EqResponseGraph({super.key, required this.params});

  final EqGraphParams params;

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      // The visible dB range eases between steps so the grid does not jump while sliders move.
      child: TweenAnimationBuilder<double>(
        tween: Tween<double>(end: params.dbTop),
        duration: const Duration(milliseconds: 320),
        curve: Curves.fastOutSlowIn,
        builder: (context, animatedDbTop, _) => SizedBox.expand(
          child: CustomPaint(
            painter: EqGraphPainter(params: params, animatedDbTop: animatedDbTop),
          ),
        ),
      ),
    );
  }
}

class EqGraphPainter extends CustomPainter {
  const EqGraphPainter({required this.params, required this.animatedDbTop});

  final EqGraphParams params;
  final double animatedDbTop;

  // Compose inset the canvas by 4dp inside a 12dp rounded surface.
  static const double _surfaceRadius = 12.0;
  static const double _canvasInset = 4.0;

  // The Compose version used these in raw pixels, not dp, so they are divided by the density.
  static const double _padLeftPx = 40.0;
  static const double _padRightPx = 8.0;
  static const double _padTopPx = 8.0;
  static const double _padBottomPx = 24.0;
  static const double _labelGapPx = 4.0;

  static const _freqLandmarks = <double>[100.0, 1000.0, 10000.0];
  static const _freqLabels = <String>['100', '1k', '10k'];

  @override
  void paint(Canvas canvas, Size size) {
    final px = 1.0 / params.density;
    canvas.drawRRect(
      RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(_surfaceRadius)),
      Paint()..color = params.surface,
    );

    final area = Rect.fromLTWH(
      _canvasInset,
      _canvasInset,
      size.width - 2 * _canvasInset,
      size.height - 2 * _canvasInset,
    );
    final plotLeft = area.left + _padLeftPx * px;
    final plotRight = area.right - _padRightPx * px;
    final plotTop = area.top + _padTopPx * px;
    final plotBottom = area.bottom - _padBottomPx * px;
    final plotWidth = plotRight - plotLeft;
    final plotHeight = plotBottom - plotTop;
    if (area.width <= 0 || area.height <= 0 || plotWidth <= 0 || plotHeight <= 0) return;

    final dbTop = animatedDbTop;
    double toX(double freq) => eqFreqToX(freq, plotLeft, plotWidth);
    double toY(double db) => eqDbToY(db, dbTop, plotTop, plotHeight);
    final labelStyle = TextStyle(fontSize: 10.0 * params.fontScale, color: params.label);

    canvas.save();
    canvas.clipRect(area);

    final gridPaint = Paint()
      ..color = params.grid
      ..strokeWidth = 0.5 * px;
    final zeroPaint = Paint()
      ..color = params.zeroLine
      ..strokeWidth = 1.5 * px;

    final lines = eqGridLines(
      gridStart: -params.dbTop,
      gridEnd: params.dbTop,
      dbStep: params.dbStep,
      animatedDbTop: dbTop,
    );
    for (final db in lines) {
      final y = toY(db);
      canvas.drawLine(Offset(plotLeft, y), Offset(plotRight, y), db.abs() < 0.01 ? zeroPaint : gridPaint);
      final text = _layout(eqFormatDb(db), labelStyle);
      text.paint(canvas, Offset(plotLeft - text.width - _labelGapPx * px, y - text.height / 2));
      text.dispose();
    }

    for (var i = 0; i < _freqLandmarks.length; i++) {
      final x = toX(_freqLandmarks[i]);
      canvas.drawLine(Offset(x, plotTop), Offset(x, plotBottom), gridPaint);
      final text = _layout(_freqLabels[i], labelStyle);
      text.paint(canvas, Offset(x - text.width / 2, plotBottom + _labelGapPx * px));
      text.dispose();
    }

    final points = params.points;
    if (points.isNotEmpty) {
      final curvePath = Path();
      final fillPath = Path();
      final zeroY = toY(0.0);
      final firstX = toX(points.first.dx);
      final firstY = toY(points.first.dy);
      curvePath.moveTo(firstX, firstY);
      fillPath
        ..moveTo(firstX, zeroY)
        ..lineTo(firstX, firstY);
      for (var i = 1; i < points.length; i++) {
        final x = toX(points[i].dx);
        final y = toY(points[i].dy);
        curvePath.lineTo(x, y);
        fillPath.lineTo(x, y);
      }
      // Fill between the curve and the 0 dB line so boosts and cuts read at a glance.
      fillPath
        ..lineTo(toX(points.last.dx), zeroY)
        ..close();

      canvas.drawPath(fillPath, Paint()..color = params.fill);
      canvas.drawPath(
        curvePath,
        Paint()
          ..color = params.curve
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2.5
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round,
      );

      if (params.showBandMarkers) {
        final ring = Paint()..color = params.markerRing;
        final dot = Paint()..color = params.curve;
        for (final marker in params.markers) {
          final center = Offset(toX(marker.dx), toY(marker.dy));
          canvas.drawCircle(center, 5.5, ring);
          canvas.drawCircle(center, 3.5, dot);
        }
      }
    }
    canvas.restore();
  }

  static TextPainter _layout(String text, TextStyle style) {
    return TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: TextDirection.ltr,
    )..layout();
  }

  @override
  bool shouldRepaint(EqGraphPainter oldDelegate) =>
      !identical(oldDelegate.params, params) || oldDelegate.animatedDbTop != animatedDbTop;
}
