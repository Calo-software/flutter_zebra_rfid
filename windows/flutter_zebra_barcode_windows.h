#ifndef FLUTTER_PLUGIN_FLUTTER_ZEBRA_BARCODE_WINDOWS_H_
#define FLUTTER_PLUGIN_FLUTTER_ZEBRA_BARCODE_WINDOWS_H_

#include "flutter_zebra_barcode.g.h"

namespace flutter_zebra_rfid {

class FlutterZebraBarcodeWindows : public flutter_zebra_barcode::FlutterZebraBarcode {
 public:
  FlutterZebraBarcodeWindows();
  ~FlutterZebraBarcodeWindows() override;

  void UpdateAvailableScanners(
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  void ConnectScanner(
      int64_t scanner_id,
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  void DisconnectScanner(
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  void RefreshBarcodeScanners(
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  void SetActiveBarcodeScanner(
      const std::string& endpoint_id,
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  void ClearActiveBarcodeScanner(
      std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) override;
  flutter_zebra_barcode::ErrorOr<std::optional<flutter_zebra_barcode::BarcodeScanner>>
  CurrentScanner() override;
  flutter_zebra_barcode::ErrorOr<std::optional<flutter_zebra_barcode::BarcodeScannerEndpoint>>
  ActiveBarcodeScanner() override;
};

void SetupFlutterZebraBarcodeWindows(
    flutter::BinaryMessenger* messenger,
    FlutterZebraBarcodeWindows* api);

}  // namespace flutter_zebra_rfid

#endif  // FLUTTER_PLUGIN_FLUTTER_ZEBRA_BARCODE_WINDOWS_H_
