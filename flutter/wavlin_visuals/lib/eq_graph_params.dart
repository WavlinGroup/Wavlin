// Parameters the Android host sends to the embedded EQ frequency response graph.
// Pure parsing so it can be tested without a screen or a platform channel.

import 'dart:ui' show Color, Offset;

class EqGraphParams {
  const EqGraphParams({
    required this.points,
    required this.markers,
    required this.dbTop,
    required this.dbStep,
    required this.showBandMarkers,
    required this.density,
    required this.fontScale,
    required this.surface,
    required this.grid,
    required this.label,
    required this.curve,
    required this.fill,
    required this.zeroLine,
    required this.markerRing,
  });

  /// (frequency in Hz, summed response in dB), ascending in frequency.
  final List<Offset> points;

  /// (band frequency in Hz, summed response in dB at that frequency).
  final List<Offset> markers;

  /// Half of the visible dB range; the graph is centered on 0 dB.
  final double dbTop;
  final double dbStep;
  final bool showBandMarkers;

  /// Android display density and font scale. The Compose graph measured some sizes in raw pixels
  /// and text in sp, so the Dart painter needs both to match it.
  final double density;
  final double fontScale;

  final Color surface;
  final Color grid;
  final Color label;
  final Color curve;
  final Color fill;
  final Color zeroLine;
  final Color markerRing;

  static const maxPoints = 2000;
  static const maxMarkers = 256;

  /// Returns null when [raw] is not a map or any field is missing, has the wrong type or is out of
  /// range. Colors are ARGB ints as produced by Compose's Color.toArgb().
  static EqGraphParams? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final points = _pairs(raw['points'], maxPoints);
    final markers = _pairs(raw['markers'], maxMarkers);
    final dbTop = _finite(raw['dbTop']);
    final dbStep = _finite(raw['dbStep']);
    final density = _finite(raw['density']);
    final fontScale = _finite(raw['fontScale']);
    final showBandMarkers = raw['showBandMarkers'];
    if (points == null || markers == null) return null;
    if (dbTop == null || dbStep == null || density == null || fontScale == null) return null;
    if (showBandMarkers is! bool) return null;
    if (dbTop <= 0 || dbTop > 1000 || dbStep <= 0 || density <= 0 || fontScale <= 0) return null;
    final surface = _color(raw['surface']);
    final grid = _color(raw['grid']);
    final label = _color(raw['label']);
    final curve = _color(raw['curve']);
    final fill = _color(raw['fill']);
    final zeroLine = _color(raw['zeroLine']);
    final markerRing = _color(raw['markerRing']);
    if (surface == null ||
        grid == null ||
        label == null ||
        curve == null ||
        fill == null ||
        zeroLine == null ||
        markerRing == null) {
      return null;
    }
    return EqGraphParams(
      points: points,
      markers: markers,
      dbTop: dbTop,
      dbStep: dbStep,
      showBandMarkers: showBandMarkers,
      density: density,
      fontScale: fontScale,
      surface: surface,
      grid: grid,
      label: label,
      curve: curve,
      fill: fill,
      zeroLine: zeroLine,
      markerRing: markerRing,
    );
  }

  /// Flat [frequency, db, frequency, db, ...] list. Frequencies must be positive and every value
  /// finite; a Float64List from the platform channel is a List<double> and passes.
  static List<Offset>? _pairs(Object? raw, int maxPairs) {
    if (raw is! List || raw.length.isOdd || raw.length ~/ 2 > maxPairs) return null;
    final out = <Offset>[];
    for (var i = 0; i < raw.length; i += 2) {
      final freq = raw[i];
      final db = raw[i + 1];
      if (freq is! num || db is! num) return null;
      final f = freq.toDouble();
      final d = db.toDouble();
      if (!f.isFinite || !d.isFinite || f <= 0) return null;
      out.add(Offset(f, d));
    }
    return out;
  }

  static double? _finite(Object? value) {
    if (value is! num) return null;
    final v = value.toDouble();
    return v.isFinite ? v : null;
  }

  static Color? _color(Object? value) => value is int ? Color(value & 0xFFFFFFFF) : null;
}
