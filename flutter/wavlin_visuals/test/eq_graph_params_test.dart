import 'dart:typed_data';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/eq_graph_params.dart';

Map<String, Object?> valid() => {
      'points': [20.0, 0.0, 1000.0, 3.5, 20000.0, -1.0],
      'markers': [1000.0, 3.5],
      'dbTop': 5.0,
      'dbStep': 2.5,
      'showBandMarkers': true,
      'density': 3.0,
      'fontScale': 1.0,
      'surface': 0xFF112233,
      'grid': 0x66FFFFFF,
      'label': -1,
      'curve': 0xFF000000,
      'fill': 0x2E000000,
      'zeroLine': 0x80000000,
      'markerRing': 0xFF112233,
    };

void main() {
  test('parses a full map', () {
    final p = EqGraphParams.tryParse(valid())!;
    expect(p.points, const [Offset(20, 0), Offset(1000, 3.5), Offset(20000, -1)]);
    expect(p.markers, const [Offset(1000, 3.5)]);
    expect(p.dbTop, 5.0);
    expect(p.dbStep, 2.5);
    expect(p.showBandMarkers, isTrue);
    expect(p.density, 3.0);
    expect(p.surface, const Color(0xFF112233));
    expect(p.label, const Color(0xFFFFFFFF)); // Kotlin toArgb() is a signed Int
  });

  test('accepts a Float64List from the platform channel and integer numbers', () {
    final map = valid()
      ..['points'] = Float64List.fromList([20, 0, 20000, 1])
      ..['dbTop'] = 5
      ..['density'] = 2;
    final p = EqGraphParams.tryParse(map)!;
    expect(p.points.length, 2);
    expect(p.dbTop, 5.0);
  });

  test('empty point lists are valid', () {
    final map = valid()
      ..['points'] = <double>[]
      ..['markers'] = <double>[];
    expect(EqGraphParams.tryParse(map), isNotNull);
  });

  test('rejects anything malformed', () {
    expect(EqGraphParams.tryParse(null), isNull);
    expect(EqGraphParams.tryParse('x'), isNull);

    for (final key in valid().keys) {
      final map = valid()..remove(key);
      expect(EqGraphParams.tryParse(map), isNull, reason: 'missing $key');
    }

    expect(EqGraphParams.tryParse(valid()..['points'] = [20.0, 0.0, 1000.0]), isNull); // odd length
    expect(EqGraphParams.tryParse(valid()..['points'] = [20.0, 'x']), isNull);
    expect(EqGraphParams.tryParse(valid()..['points'] = [double.nan, 0.0]), isNull);
    expect(EqGraphParams.tryParse(valid()..['points'] = [0.0, 0.0]), isNull); // frequency must be positive
    expect(EqGraphParams.tryParse(valid()..['points'] = [-5.0, 0.0]), isNull);
    expect(EqGraphParams.tryParse(valid()..['markers'] = [double.infinity, 0.0]), isNull);
    expect(EqGraphParams.tryParse(valid()..['dbTop'] = 0.0), isNull);
    expect(EqGraphParams.tryParse(valid()..['dbTop'] = 5000.0), isNull);
    expect(EqGraphParams.tryParse(valid()..['dbTop'] = double.nan), isNull);
    expect(EqGraphParams.tryParse(valid()..['dbStep'] = 0.0), isNull);
    expect(EqGraphParams.tryParse(valid()..['density'] = 0.0), isNull);
    expect(EqGraphParams.tryParse(valid()..['fontScale'] = -1.0), isNull);
    expect(EqGraphParams.tryParse(valid()..['showBandMarkers'] = 1), isNull);
    expect(EqGraphParams.tryParse(valid()..['curve'] = 'red'), isNull);
  });

  test('bounds the number of points and markers', () {
    final tooManyPoints = List<double>.generate((EqGraphParams.maxPoints + 1) * 2, (i) => i.isEven ? 20.0 : 0.0);
    expect(EqGraphParams.tryParse(valid()..['points'] = tooManyPoints), isNull);
    final tooManyMarkers = List<double>.generate((EqGraphParams.maxMarkers + 1) * 2, (i) => i.isEven ? 20.0 : 0.0);
    expect(EqGraphParams.tryParse(valid()..['markers'] = tooManyMarkers), isNull);
  });
}
