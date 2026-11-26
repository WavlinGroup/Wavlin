import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/wavlin_visuals.dart';

void main() {
  test('generation is deterministic and within the Kotlin ranges', () {
    final a = generateBgElements(count: 50, shapes: const [BgShape.circle, BgShape.rect], seed: 42);
    final b = generateBgElements(count: 50, shapes: const [BgShape.circle, BgShape.rect], seed: 42);
    expect(a.length, 50);
    for (var i = 0; i < a.length; i++) {
      expect(a[i].initialX, b[i].initialX);
      expect(a[i].shape, b[i].shape);
      final e = a[i];
      expect(e.initialX, inInclusiveRange(0, 1));
      expect(e.targetY, inInclusiveRange(0, 1));
      expect(e.alpha, inInclusiveRange(0.1, 0.4));
      expect(e.durationMs, inInclusiveRange(4000, 9999));
      if (e.shape == BgShape.circle) {
        expect(e.size, inInclusiveRange(5, 20));
      } else {
        expect(e.size, inInclusiveRange(10, 60));
      }
    }
  });

  test('empty inputs give no elements', () {
    expect(generateBgElements(count: 0, shapes: const [BgShape.line], seed: 1), isEmpty);
    expect(generateBgElements(count: 5, shapes: const [], seed: 1), isEmpty);
  });

  test('reverseProgress goes 0 -> 1 -> 0', () {
    expect(reverseProgress(0, 1000), 0);
    expect(reverseProgress(500, 1000), 0.5);
    expect(reverseProgress(1000, 1000), 1);
    expect(reverseProgress(1500, 1000), 0.5);
    expect(reverseProgress(2000, 1000), 0);
    expect(reverseProgress(2500, 1000), 0.5);
    expect(reverseProgress(10, 0), 0);
  });

  test('positionAt interpolates', () {
    const e = BgElement(
      shape: BgShape.circle,
      initialX: 0.2,
      initialY: 0.4,
      targetX: 0.6,
      targetY: 0.0,
      size: 5,
      alpha: 0.2,
      durationMs: 4000,
    );
    final (x, y) = positionAt(e, 0.5);
    expect(x, closeTo(0.4, 1e-9));
    expect(y, closeTo(0.2, 1e-9));
  });

  test('params parsing', () {
    final p = WrappedBackgroundParams.tryParse({'elementCount': 999, 'shapes': ['line'], 'seed': 7})!;
    expect(p.elementCount, WrappedBackgroundParams.maxElements);
    expect(p.shapes, [BgShape.line]);
    expect(WrappedBackgroundParams.tryParse({'elementCount': 5, 'shapes': ['star'], 'seed': 1}), isNull);
    expect(WrappedBackgroundParams.tryParse({'elementCount': 5, 'shapes': <String>[], 'seed': 1}), isNull);
    expect(WrappedBackgroundParams.tryParse({'elementCount': -3, 'shapes': ['rect'], 'seed': 1})!.elementCount, 0);
    expect(WrappedBackgroundParams.tryParse(null), isNull);
  });
}
