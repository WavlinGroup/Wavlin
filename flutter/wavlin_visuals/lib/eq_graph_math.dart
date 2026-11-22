// Pure helpers for the EQ frequency response graph, ported from EqFrequencyResponseGraph.kt.

import 'dart:math' as math;

const eqMinFreq = 20.0;
const eqMaxFreq = 20000.0;

double eqLog10(double value) => math.log(value) / math.ln10;

/// Logarithmic frequency axis from [eqMinFreq] to [eqMaxFreq] across [plotWidth].
double eqFreqToX(double freq, double plotLeft, double plotWidth) {
  final logMin = eqLog10(eqMinFreq);
  final logMax = eqLog10(eqMaxFreq);
  return plotLeft + (eqLog10(freq) - logMin) / (logMax - logMin) * plotWidth;
}

/// Linear dB axis, +[dbTop] at [plotTop] down to -[dbTop] at the bottom of [plotHeight].
double eqDbToY(double db, double dbTop, double plotTop, double plotHeight) {
  return plotTop + (dbTop - db) / (2.0 * dbTop) * plotHeight;
}

/// "0", whole numbers without decimals, everything else with one decimal.
String eqFormatDb(double db) {
  if (db == 0.0) return '0';
  if (db == db.truncateToDouble()) return db.toInt().toString();
  return db.toStringAsFixed(1);
}

/// Grid line dB values for the target range [gridStart]..[gridEnd] that fit inside the currently
/// animated half range [animatedDbTop], so lines appear as the range eases out to them.
List<double> eqGridLines({
  required double gridStart,
  required double gridEnd,
  required double dbStep,
  required double animatedDbTop,
}) {
  final lines = <double>[];
  if (!(dbStep > 0) || !gridStart.isFinite || !gridEnd.isFinite) return lines;
  var db = gridStart;
  // Bounded so a bad payload cannot spin the paint loop.
  for (var i = 0; i < 400 && db <= gridEnd; i++) {
    if (db.abs() <= animatedDbTop + 0.01) lines.add(db);
    db += dbStep;
  }
  return lines;
}
