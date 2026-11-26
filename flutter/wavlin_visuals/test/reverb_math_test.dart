import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/wavlin_visuals.dart';

void main() {
  test('clamp01 handles range and NaN', () {
    expect(clamp01(-1), 0.0);
    expect(clamp01(2), 1.0);
    expect(clamp01(0.25), 0.25);
    expect(clamp01(double.nan), 0.0);
  });

  test('pulsePhase wraps once per pulse', () {
    expect(pulsePhase(0), 0.0);
    expect(pulsePhase(pulseSeconds / 2), closeTo(0.5, 1e-12));
    expect(pulsePhase(pulseSeconds * 3 + 0.26), closeTo(0.1, 1e-9));
  });

  test('ping-pong swing and flash', () {
    expect(pingPongSwing(0), 1.0);
    expect(pingPongSwing(0.1), closeTo(-0.42681973, 1e-6));
    final f = pingPongFlash(0);
    expect(f.right, closeTo(1.0, 1e-9));
    expect(f.left, 0.0);
    for (var p = 0.0; p < 1.0; p += 0.01) {
      final v = pingPongFlash(p);
      expect(v.right, inInclusiveRange(0.0, 1.0));
      expect(v.left, inInclusiveRange(0.0, 1.0));
    }
  });

  test('swell bars stay in range and are symmetric', () {
    for (var i = 0; i < 29; i++) {
      final bar = swellBar(i, 1.7, 0.4);
      expect(bar.a, inInclusiveRange(0.0, 1.0));
      expect(bar.d, inInclusiveRange(0.0, 1.0));
    }
    expect(swellBar(14, 0, 0).d, 0.0);
    expect(swellBar(0, 0, 0).d, 1.0);
    expect(swellBar(28, 0, 0).d, closeTo(1.0, 1e-12));
    expect(swellBar(3, 2.0, 0.3).d, closeTo(swellBar(25, 2.0, 0.3).d, 1e-12));
  });

  test('plate magnitude is zero on the corner and bounded elsewhere', () {
    expect(plateMagnitude(0, 0, 1, 1), 0.0);
    for (var j = 0; j < 7; j++) {
      for (var i = 0; i < 19; i++) {
        for (final c in [-1.0, -0.3, 0.0, 0.8, 1.0]) {
          expect(plateMagnitude(i / 18, j / 6, c, -c), inInclusiveRange(0.0, 1.0));
        }
      }
    }
  });

  test('echo rings start in order and fade', () {
    expect(echoRing(1, 0.1), isNull);
    final first = echoRing(0, 0.0)!;
    expect(first.r, 0.0);
    final k1 = echoRing(1, 0.5)!;
    expect(k1.r, closeTo(0.390244, 1e-5));
    expect(k1.alpha, closeTo(0.390223, 1e-5));
    expect(echoRing(0, 0.99)!.alpha, lessThan(0.01));
  });

  test('coil endpoints', () {
    expect(coilParams(0, 240, 0).u, 0.0);
    expect(coilParams(240, 240, 0).u, closeTo(1.0, 1e-6));
  });
}
