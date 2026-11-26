import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/reverb_params.dart';

void main() {
  test('parses a full map', () {
    final p = ReverbParams.tryParse({
      'presetIndex': 3,
      'enabled': true,
      'speed': 1,
      'primary': 0xFF112233,
      'secondary': -1,
      'tertiary': 0xFF000000,
      'muted': 0x80FFFFFF,
    })!;
    expect(p.presetIndex, 3);
    expect(p.enabled, isTrue);
    expect(p.speed, 1.0);
    expect(p.primary, const Color(0xFF112233));
    expect(p.secondary, const Color(0xFFFFFFFF)); // Kotlin toArgb() is a signed Int
    expect(p.muted, const Color(0x80FFFFFF));
  });

  test('missing colors stay null', () {
    final p = ReverbParams.tryParse({'presetIndex': 0, 'enabled': false, 'speed': 1.5})!;
    expect(p.primary, isNull);
    expect(p.speed, 1.5);
  });

  test('rejects wrong types and non-finite speed', () {
    expect(ReverbParams.tryParse(null), isNull);
    expect(ReverbParams.tryParse({'presetIndex': '1', 'enabled': true, 'speed': 1.0}), isNull);
    expect(ReverbParams.tryParse({'presetIndex': 1, 'enabled': 1, 'speed': 1.0}), isNull);
    expect(ReverbParams.tryParse({'presetIndex': 1, 'enabled': true, 'speed': double.nan}), isNull);
  });
}
