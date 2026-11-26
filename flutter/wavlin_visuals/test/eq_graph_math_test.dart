import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/eq_graph_math.dart';

void main() {
  test('frequency axis is logarithmic and spans the plot', () {
    expect(eqFreqToX(20, 40, 300), closeTo(40, 1e-9));
    expect(eqFreqToX(20000, 40, 300), closeTo(340, 1e-9));
    // 632.4 Hz is the geometric middle of 20 Hz and 20 kHz.
    expect(eqFreqToX(632.4555320336759, 40, 300), closeTo(190, 1e-6));
  });

  test('dB axis maps +top to the plot top and -top to the bottom', () {
    expect(eqDbToY(5, 5, 8, 100), closeTo(8, 1e-9));
    expect(eqDbToY(0, 5, 8, 100), closeTo(58, 1e-9));
    expect(eqDbToY(-5, 5, 8, 100), closeTo(108, 1e-9));
  });

  test('dB labels', () {
    expect(eqFormatDb(0), '0');
    expect(eqFormatDb(5), '5');
    expect(eqFormatDb(-10), '-10');
    expect(eqFormatDb(2.5), '2.5');
    expect(eqFormatDb(-7.5), '-7.5');
  });

  test('grid lines follow the target range but only show inside the animated range', () {
    final all = eqGridLines(gridStart: -5, gridEnd: 5, dbStep: 2.5, animatedDbTop: 5);
    expect(all, [-5.0, -2.5, 0.0, 2.5, 5.0]);

    final shrinking = eqGridLines(gridStart: -5, gridEnd: 5, dbStep: 2.5, animatedDbTop: 2.5);
    expect(shrinking, [-2.5, 0.0, 2.5]);
  });

  test('grid lines are bounded for bad input', () {
    expect(eqGridLines(gridStart: -5, gridEnd: 5, dbStep: 0, animatedDbTop: 5), isEmpty);
    expect(eqGridLines(gridStart: -5, gridEnd: 5, dbStep: -1, animatedDbTop: 5), isEmpty);
    expect(eqGridLines(gridStart: double.nan, gridEnd: 5, dbStep: 1, animatedDbTop: 5), isEmpty);
    expect(
      eqGridLines(gridStart: -1e9, gridEnd: 1e9, dbStep: 1e-6, animatedDbTop: 1e9).length,
      lessThanOrEqualTo(400),
    );
  });
}
