// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/wavlin_visuals.dart';

Widget _host(Widget child) => MaterialApp(
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.pink)),
      home: Scaffold(body: Center(child: child)),
    );

void main() {
  testWidgets('every preset paints frames without errors', (tester) async {
    for (var preset = 0; preset <= 5; preset++) {
      await tester.pumpWidget(_host(ReverbRoomVisualizer(presetIndex: preset, enabled: true)));
      for (var i = 0; i < 8; i++) {
        await tester.pump(const Duration(milliseconds: 120));
      }
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('switching presets and disabling does not throw', (tester) async {
    await tester.pumpWidget(_host(const ReverbRoomVisualizer(presetIndex: 1, enabled: true)));
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pumpWidget(_host(const ReverbRoomVisualizer(presetIndex: 5, enabled: true)));
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pumpWidget(_host(const ReverbRoomVisualizer(presetIndex: 5, enabled: false)));
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });

  testWidgets('widget is 130 logical pixels tall', (tester) async {
    await tester.pumpWidget(_host(const ReverbRoomVisualizer(presetIndex: 0, enabled: false)));
    final size = tester.getSize(find.byType(ReverbRoomVisualizer));
    expect(size.height, 130);
  });
}
