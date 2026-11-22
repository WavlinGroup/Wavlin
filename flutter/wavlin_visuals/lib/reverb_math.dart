// Pure animation math for the reverb visualizer, ported from ReverbRoomVisualizer.kt.
// Kotlin used 32-bit floats; Dart doubles differ in the last digits, far below anything visible.

import 'dart:math' as math;

const double tau = 6.2831855;
const double piF = 3.1415927;
const double pulseSeconds = 2.6;

/// Preset indices, same order as ReverbManager: 0 None, 1 Small Room, 2 Concert Hall,
/// 3 Stadium, 4 Plate, 5 Spring.
const int presetNone = 0;
const int presetRoom = 1;
const int presetHall = 2;
const int presetStadium = 3;
const int presetPlate = 4;
const int presetSpring = 5;

double clamp01(double v) => v.isNaN ? 0.0 : v.clamp(0.0, 1.0).toDouble();

/// Position inside the current pulse, in [0, 1) for non-negative [t].
double pulsePhase(double t) => (t / pulseSeconds) % 1.0;

/// Horizontal swing of the ping-pong dot as a fraction of its reach (-1..1).
double pingPongSwing(double p) => math.exp(-3.2 * p) * math.cos(tau * 3.5 * p);

/// Wall flash strengths (0..1) at [phase] for the right and left wall.
({double right, double left}) pingPongFlash(double phase) {
  final swing = math.cos(tau * 3.5 * phase);
  final fade = math.exp(-3.2 * phase);
  return (
    right: math.pow(math.max(0.0, swing), 12).toDouble() * fade,
    left: math.pow(math.max(0.0, -swing), 12).toDouble() * fade,
  );
}

/// Height factor `a` (0..1) of bar [i] of [bars] in the swell visualization, and its distance
/// `d` (0 at the middle, 1 at the edges) used for the color blend.
({double a, double d}) swellBar(int i, double t, double phase, {int bars = 29}) {
  final u = i / (bars - 1.0);
  final d = (u - 0.5).abs() * 2.0;
  final front = phase * 1.25;
  final ring = math.exp(-((d - front) * (d - front)) / 0.025) * (1.0 - phase * 0.55);
  final tail = 0.16 * (1.0 - d * 0.6) * (0.7 + 0.3 * math.sin(t * 2.1 + i * 0.6));
  return (a: clamp01(ring + tail), d: d);
}

/// Normalized vibration (0..1) of the plate dot at grid position ([x], [y]) in [0, 1].
double plateMagnitude(double x, double y, double c1, double c2) {
  final v = math.sin(3.0 * piF * x) * math.sin(2.0 * piF * y) * c1 +
      0.55 * math.sin(piF * x) * math.sin(4.0 * piF * y) * c2;
  return math.min(v.abs(), 1.0);
}

/// Echo ring [k] at [phase]: relative radius and alpha, or null while it has not started.
({double r, double alpha})? echoRing(int k, double phase) {
  final delay = k * 0.18;
  if (phase < delay) return null;
  final r = (phase - delay) / (1.0 - delay);
  final alpha = clamp01(math.pow(1.0 - r, 1.4).toDouble() * (1.0 - k * 0.22));
  return (r: r, alpha: alpha);
}

/// Parameters of point [i] of [steps] on the spring coil: [u] is the position along the coil
/// as a fraction of its length, [angle] the winding angle, [wave] the travelling wave value.
({double u, double angle, double wave}) coilParams(int i, int steps, double t, {int loops = 8}) {
  final s = i / steps.toDouble();
  final wave = math.sin(tau * (s * 1.6 - t * 0.75));
  final u = s + 0.022 * wave * math.sin(piF * s);
  return (u: u, angle: tau * loops * u, wave: wave);
}
