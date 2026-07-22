import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';

void main() {
  test('percentage is an explicit clamped alias for the legacy level field',
      () {
    final battery = BatteryData(
      level: 73,
      isCharging: false,
      cause: 'test',
      source: BatteryDataSource.readerStatistics,
      isPercentageEstimated: false,
    );

    expect(battery.percentage, 73);
    expect(battery.sourceLabel, 'Zebra battery statistics');
  });

  test('legacy BatteryData construction remains source compatible', () {
    final battery = BatteryData(
      level: 120,
      isCharging: true,
      cause: 'legacy',
    );

    expect(battery.percentage, 100);
    expect(battery.sourceLabel, 'Legacy battery event');
  });
}
