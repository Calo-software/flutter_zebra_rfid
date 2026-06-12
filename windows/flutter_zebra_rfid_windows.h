#ifndef FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_WINDOWS_H_
#define FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_WINDOWS_H_

#include "flutter_zebra_rfid.g.h"
#include "zebra_rfid_helper_bridge.h"

#include <memory>

namespace flutter_zebra_rfid {

class FlutterZebraRfidWindows : public FlutterZebraRfid {
 public:
  explicit FlutterZebraRfidWindows(flutter::BinaryMessenger* messenger);
  ~FlutterZebraRfidWindows() override;

  void UpdateAvailableReaders(
      const ReaderConnectionType& connection_type,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void StartBluetoothScan(std::function<void(std::optional<FlutterError> reply)> result) override;
  void StopBluetoothScan(std::function<void(std::optional<FlutterError> reply)> result) override;
  void GetBondedDevices(std::function<void(ErrorOr<flutter::EncodableList> reply)> result) override;
  void PairBluetoothDevice(
      const std::string& address,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void ConnectReader(
      int64_t reader_id,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void ConfigureReader(
      const ReaderConfig& config,
      bool should_persist,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void DisconnectReader(std::function<void(std::optional<FlutterError> reply)> result) override;
  void TriggerDeviceStatus(std::function<void(std::optional<FlutterError> reply)> result) override;
  void StartLocating(
      const flutter::EncodableList& tags,
      const bool* disable_beep,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void StopLocating(std::function<void(std::optional<FlutterError> reply)> result) override;
  void ResetLocateState(std::function<void(std::optional<FlutterError> reply)> result) override;
  ErrorOr<std::optional<Reader>> CurrentReader() override;
  void ReaderConfig(std::function<void(ErrorOr<flutter_zebra_rfid::ReaderConfig> reply)> result) override;
  void SupportedReaderRegions(std::function<void(ErrorOr<flutter::EncodableList> reply)> result) override;
  void SetReaderRegion(
      const std::string& region_code,
      std::function<void(std::optional<FlutterError> reply)> result) override;
  void Diagnostics(std::function<void(ErrorOr<flutter_zebra_rfid::Diagnostics> reply)> result) override;
  void SetScanningEnabled(
      bool enabled,
      std::function<void(std::optional<FlutterError> reply)> result) override;

 private:
  void HandleHelperEvent(const std::string& json);

  std::unique_ptr<FlutterZebraRfidCallbacks> callbacks_;
  std::unique_ptr<ZebraRfidHelperBridge> helper_;
};

}  // namespace flutter_zebra_rfid

#endif  // FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_WINDOWS_H_
