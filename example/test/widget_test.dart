// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_zebra_rfid_example/main.dart';

void main() {
  testWidgets('Shows RFID actions on launch', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    expect(find.text('Zebra RFID + Barcode example'), findsOneWidget);
    expect(find.text('Pair Reader'), findsOneWidget);
    expect(find.text('Get Reader List'), findsOneWidget);
  });
}
