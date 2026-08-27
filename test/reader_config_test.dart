import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';

void main() {
  test('Capture Device preserves RFID Reader inventory settings', () {
    const expectedSessions =
        <ReaderInventorySession, CaptureReaderInventorySession>{
      ReaderInventorySession.s0: CaptureReaderInventorySession.s0,
      ReaderInventorySession.s1: CaptureReaderInventorySession.s1,
      ReaderInventorySession.s2: CaptureReaderInventorySession.s2,
      ReaderInventorySession.s3: CaptureReaderInventorySession.s3,
    };

    for (final entry in expectedSessions.entries) {
      final captureConfig = ReaderConfig(
        inventorySession: entry.key,
        estimatedTagPopulation: 200,
        uniqueTagReporting: true,
      ).toCaptureReaderConfig();

      expect(captureConfig.inventorySession, entry.value);
      expect(captureConfig.estimatedTagPopulation, 200);
      expect(captureConfig.uniqueTagReporting, isTrue);
    }
  });
}
