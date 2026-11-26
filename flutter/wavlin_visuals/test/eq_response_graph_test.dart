import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wavlin_visuals/eq_graph_params.dart';
import 'package:wavlin_visuals/eq_response_graph.dart';

EqGraphParams params({double dbTop = 5.0, bool markers = true, List<Offset> points = const []}) {
  return EqGraphParams(
    points: points,
    markers: const [Offset(1000, 2)],
    dbTop: dbTop,
    dbStep: 2.5,
    showBandMarkers: markers,
    density: 3.0,
    fontScale: 1.0,
    surface: const Color(0xFF202020),
    grid: const Color(0x66FFFFFF),
    label: const Color(0xB3FFFFFF),
    curve: const Color(0xFF80CBC4),
    fill: const Color(0x2E80CBC4),
    zeroLine: const Color(0x8080CBC4),
    markerRing: const Color(0xFF202020),
  );
}

Widget host(EqGraphParams p) => Directionality(
      textDirection: TextDirection.ltr,
      child: SizedBox(width: 360, height: 190, child: EqResponseGraph(params: p)),
    );

void main() {
  const curve = [Offset(20, 0), Offset(1000, 2), Offset(20000, -1)];

  testWidgets('paints a curve with markers without errors', (tester) async {
    await tester.pumpWidget(host(params(points: curve)));
    expect(tester.takeException(), isNull);
    expect(find.byType(CustomPaint), findsWidgets);
  });

  testWidgets('paints an empty curve and a tiny box without errors', (tester) async {
    await tester.pumpWidget(host(params()));
    expect(tester.takeException(), isNull);

    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: SizedBox(width: 20, height: 20, child: EqResponseGraph(params: params(points: curve))),
      ),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('eases the visible range when dbTop changes', (tester) async {
    await tester.pumpWidget(host(params(points: curve)));
    await tester.pumpWidget(host(params(dbTop: 10.0, points: curve)));
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.takeException(), isNull);
    await tester.pump(const Duration(milliseconds: 400));
    expect(tester.takeException(), isNull);
  });
}
