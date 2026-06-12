#include "flutter_zebra_barcode_windows.h"

namespace flutter_zebra_rfid {
namespace {

flutter_zebra_barcode::FlutterError Unsupported() {
  return flutter_zebra_barcode::FlutterError(
      "unsupported",
      "Zebra barcode APIs are not supported by the Windows implementation yet.");
}

}  // namespace

FlutterZebraBarcodeWindows::FlutterZebraBarcodeWindows() = default;
FlutterZebraBarcodeWindows::~FlutterZebraBarcodeWindows() = default;

void FlutterZebraBarcodeWindows::UpdateAvailableScanners(
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

void FlutterZebraBarcodeWindows::ConnectScanner(
    int64_t scanner_id,
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

void FlutterZebraBarcodeWindows::DisconnectScanner(
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

void FlutterZebraBarcodeWindows::RefreshBarcodeScanners(
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

void FlutterZebraBarcodeWindows::SetActiveBarcodeScanner(
    const std::string& endpoint_id,
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

void FlutterZebraBarcodeWindows::ClearActiveBarcodeScanner(
    std::function<void(std::optional<flutter_zebra_barcode::FlutterError> reply)> result) {
  result(Unsupported());
}

flutter_zebra_barcode::ErrorOr<std::optional<flutter_zebra_barcode::BarcodeScanner>>
FlutterZebraBarcodeWindows::CurrentScanner() {
  return std::optional<flutter_zebra_barcode::BarcodeScanner>();
}

flutter_zebra_barcode::ErrorOr<std::optional<flutter_zebra_barcode::BarcodeScannerEndpoint>>
FlutterZebraBarcodeWindows::ActiveBarcodeScanner() {
  return std::optional<flutter_zebra_barcode::BarcodeScannerEndpoint>();
}

void SetupFlutterZebraBarcodeWindows(
    flutter::BinaryMessenger* messenger,
    FlutterZebraBarcodeWindows* api) {
  flutter_zebra_barcode::FlutterZebraBarcode::SetUp(messenger, api);
}

}  // namespace flutter_zebra_rfid
