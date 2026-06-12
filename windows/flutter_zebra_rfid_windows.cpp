#include "flutter_zebra_rfid_windows.h"

#include <regex>
#include <sstream>

namespace flutter_zebra_rfid {
namespace {

FlutterError Unsupported(const std::string& feature) {
  return FlutterError("unsupported", feature + " is not supported by the Windows RFID implementation yet.");
}

FlutterError ToFlutterError(const HelperResult& result) {
  return FlutterError(result.error_code.empty() ? "unknown" : result.error_code,
                      result.error_message.empty() ? "RFID helper failed." : result.error_message,
                      flutter::EncodableValue(result.error_details));
}

std::string ConnectionTypeName(const ReaderConnectionType& type) {
  switch (type) {
    case ReaderConnectionType::kUsb:
      return "usb";
    case ReaderConnectionType::kBluetooth:
      return "bluetooth";
    case ReaderConnectionType::kAll:
      return "all";
  }
  return "all";
}

ReaderConnectionStatus StatusFromString(const std::string& value) {
  if (value == "connecting") return ReaderConnectionStatus::kConnecting;
  if (value == "connected") return ReaderConnectionStatus::kConnected;
  if (value == "disconnecting") return ReaderConnectionStatus::kDisconnecting;
  if (value == "error") return ReaderConnectionStatus::kError;
  return ReaderConnectionStatus::kDisconnected;
}

ReaderErrorCode ErrorCodeFromString(const std::string& value) {
  if (value == "noAvailableReaders") return ReaderErrorCode::kNoAvailableReaders;
  if (value == "invalidReaderIndex") return ReaderErrorCode::kInvalidReaderIndex;
  if (value == "readerDeviceNull") return ReaderErrorCode::kReaderDeviceNull;
  if (value == "alreadyConnecting") return ReaderErrorCode::kAlreadyConnecting;
  if (value == "notConnected") return ReaderErrorCode::kNotConnected;
  if (value == "sdkInvalidUsage") return ReaderErrorCode::kSdkInvalidUsage;
  if (value == "sdkOperationFailure") return ReaderErrorCode::kSdkOperationFailure;
  if (value == "timeout") return ReaderErrorCode::kTimeout;
  return ReaderErrorCode::kUnknown;
}

flutter::EncodableList IntArray(const std::string& json, const std::string& key) {
  flutter::EncodableList list;
  const std::string array = JsonArray(json, key);
  std::regex number_regex("-?[0-9]+");
  for (auto it = std::sregex_iterator(array.begin(), array.end(), number_regex);
       it != std::sregex_iterator(); ++it) {
    list.emplace_back(static_cast<int64_t>(std::stoll((*it)[0].str())));
  }
  return list;
}

ReaderInfo ParseReaderInfo(const std::string& json) {
  ReaderInfo info(IntArray(json, "transmitPowerLevels"));
  if (const auto value = JsonString(json, "firmwareVersion")) {
    info.set_firmware_version(*value);
  }
  if (const auto value = JsonString(json, "modelVersion")) {
    info.set_model_version(*value);
  }
  if (const auto value = JsonString(json, "scannerName")) {
    info.set_scanner_name(*value);
  }
  if (const auto value = JsonString(json, "serialNumber")) {
    info.set_serial_number(*value);
  }
  return info;
}

Reader ParseReader(const std::string& json) {
  const int64_t id = JsonInt(json, "id").value_or(0);
  const std::optional<std::string> name = JsonString(json, "name");
  const std::string info_json = JsonObject(json, "info");
  ReaderInfo info = ParseReaderInfo(info_json);
  return Reader(name ? &(*name) : nullptr, id, &info);
}

flutter::EncodableList ParseReaders(const std::string& json) {
  flutter::EncodableList readers;
  for (const auto& reader_json : JsonObjects(JsonArray(json, "readers"))) {
    readers.emplace_back(flutter::CustomEncodableValue(ParseReader(reader_json)));
  }
  return readers;
}

RfidTag ParseTag(const std::string& json) {
  const std::string id = JsonString(json, "id").value_or("");
  const int64_t rssi = JsonInt(json, "rssi").value_or(0);
  return RfidTag(id, rssi);
}

flutter::EncodableList ParseTags(const std::string& json) {
  flutter::EncodableList tags;
  for (const auto& tag_json : JsonObjects(JsonArray(json, "tags"))) {
    tags.emplace_back(flutter::CustomEncodableValue(ParseTag(tag_json)));
  }
  return tags;
}

flutter_zebra_rfid::ReaderConfig ParseReaderConfig(const std::string& json) {
  flutter_zebra_rfid::ReaderConfig config;
  if (const auto value = JsonInt(json, "transmitPowerIndex")) {
    config.set_transmit_power_index(*value);
  }
  if (const auto value = JsonInt(json, "tari")) {
    config.set_tari(*value);
  }
  if (const auto value = JsonInt(json, "rfModeTableIndex")) {
    config.set_rf_mode_table_index(*value);
  }
  if (const auto value = JsonInt(json, "receiveSensitivityIndex")) {
    config.set_receive_sensitivity_index(*value);
  }
  return config;
}

flutter_zebra_rfid::Diagnostics ParseDiagnostics(const std::string& json) {
  flutter_zebra_rfid::Diagnostics diagnostics(
      StatusFromString(JsonString(json, "connectionState").value_or("disconnected")),
      JsonInt(json, "connectAttempts").value_or(0),
      false);
  if (const auto value = JsonString(json, "lastErrorCode")) {
    diagnostics.set_last_error_code(ErrorCodeFromString(*value));
  }
  if (const auto value = JsonString(json, "lastErrorMessage")) {
    diagnostics.set_last_error_message(*value);
  }
  if (const auto value = JsonInt(json, "lastConnectStartMs")) {
    diagnostics.set_last_connect_start_ms(*value);
  }
  if (const auto value = JsonInt(json, "lastConnectDurationMs")) {
    diagnostics.set_last_connect_duration_ms(*value);
  }
  if (const auto value = JsonBool(json, "isLocating")) {
    diagnostics.set_is_locating(*value);
  }
  if (const auto value = JsonBool(json, "scanningEnabled")) {
    diagnostics.set_scanning_enabled(*value);
  }
  if (const auto value = JsonInt(json, "scanningEnabledLastToggleMs")) {
    diagnostics.set_scanning_enabled_last_toggle_ms(*value);
  }
  if (const auto value = JsonBool(json, "inventoryActive")) {
    diagnostics.set_inventory_active(*value);
  }
  if (const auto value = JsonInt(json, "lastInventoryStartMs")) {
    diagnostics.set_last_inventory_start_ms(*value);
  }
  if (const auto value = JsonInt(json, "lastInventoryStopMs")) {
    diagnostics.set_last_inventory_stop_ms(*value);
  }
  if (const auto value = JsonBool(json, "pendingPurgeActive")) {
    diagnostics.set_pending_purge_active(*value);
  }
  if (const auto value = JsonString(json, "lastInventoryStopReason")) {
    diagnostics.set_last_inventory_stop_reason(*value);
  }
  if (const auto value = JsonString(json, "lastInventoryStartReason")) {
    diagnostics.set_last_inventory_start_reason(*value);
  }
  return diagnostics;
}

std::string ConfigJson(const flutter_zebra_rfid::ReaderConfig& config) {
  std::ostringstream json;
  json << "{";
  json << "\"transmitPowerIndex\":";
  if (config.transmit_power_index()) {
    json << *config.transmit_power_index();
  } else {
    json << "null";
  }
  json << ",\"tari\":";
  if (config.tari()) {
    json << *config.tari();
  } else {
    json << "null";
  }
  json << ",\"rfModeTableIndex\":";
  if (config.rf_mode_table_index()) {
    json << *config.rf_mode_table_index();
  } else {
    json << "null";
  }
  json << ",\"receiveSensitivityIndex\":";
  if (config.receive_sensitivity_index()) {
    json << *config.receive_sensitivity_index();
  } else {
    json << "null";
  }
  json << "}";
  return json.str();
}

void IgnoreCallbackSuccess() {}

void IgnoreCallbackError(const FlutterError& error) {}

}  // namespace

FlutterZebraRfidWindows::FlutterZebraRfidWindows(flutter::BinaryMessenger* messenger) {
  callbacks_ = std::make_unique<FlutterZebraRfidCallbacks>(messenger);
  helper_ = std::make_unique<ZebraRfidHelperBridge>(
      [this](const std::string& json) { HandleHelperEvent(json); });
}

FlutterZebraRfidWindows::~FlutterZebraRfidWindows() = default;

void FlutterZebraRfidWindows::UpdateAvailableReaders(
    const ReaderConnectionType& connection_type,
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send(
      "discoverReaders",
      "{\"connectionType\":\"" + ConnectionTypeName(connection_type) + "\"}");
  if (!helper_result.ok) {
    result(ToFlutterError(helper_result));
    return;
  }
  const std::string data = JsonObject(helper_result.json, "data");
  callbacks_->OnAvailableReadersChanged(ParseReaders(data), IgnoreCallbackSuccess,
                                        IgnoreCallbackError);
  result(std::nullopt);
}

void FlutterZebraRfidWindows::StartBluetoothScan(
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Bluetooth scanning"));
}

void FlutterZebraRfidWindows::StopBluetoothScan(
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Bluetooth scanning"));
}

void FlutterZebraRfidWindows::GetBondedDevices(
    std::function<void(ErrorOr<flutter::EncodableList> reply)> result) {
  result(Unsupported("Bluetooth bonded device lookup"));
}

void FlutterZebraRfidWindows::PairBluetoothDevice(
    const std::string& address,
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Bluetooth pairing"));
}

void FlutterZebraRfidWindows::ConnectReader(
    int64_t reader_id,
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send(
      "connectReader", "{\"readerId\":" + std::to_string(reader_id) + "}");
  result(helper_result.ok ? std::optional<FlutterError>()
                          : std::optional<FlutterError>(ToFlutterError(helper_result)));
}

void FlutterZebraRfidWindows::ConfigureReader(
    const flutter_zebra_rfid::ReaderConfig& config,
    bool should_persist,
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send(
      "configureReader",
      "{\"config\":" + ConfigJson(config) + ",\"shouldPersist\":" +
          (should_persist ? "true" : "false") + "}");
  result(helper_result.ok ? std::optional<FlutterError>()
                          : std::optional<FlutterError>(ToFlutterError(helper_result)));
}

void FlutterZebraRfidWindows::DisconnectReader(
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send("disconnectReader", "{}");
  result(helper_result.ok ? std::optional<FlutterError>()
                          : std::optional<FlutterError>(ToFlutterError(helper_result)));
}

void FlutterZebraRfidWindows::TriggerDeviceStatus(
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send("triggerDeviceStatus", "{}");
  result(helper_result.ok ? std::optional<FlutterError>()
                          : std::optional<FlutterError>(ToFlutterError(helper_result)));
}

void FlutterZebraRfidWindows::StartLocating(
    const flutter::EncodableList& tags,
    const bool* disable_beep,
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Tag locating"));
}

void FlutterZebraRfidWindows::StopLocating(
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Tag locating"));
}

void FlutterZebraRfidWindows::ResetLocateState(
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(std::nullopt);
}

ErrorOr<std::optional<Reader>> FlutterZebraRfidWindows::CurrentReader() {
  const HelperResult helper_result = helper_->Send("currentReader", "{}");
  if (!helper_result.ok) {
    return ToFlutterError(helper_result);
  }
  const std::string data = JsonObject(helper_result.json, "data");
  const std::string reader = JsonObject(data, "reader");
  if (reader.empty() || reader == "null") {
    return std::optional<Reader>();
  }
  return std::optional<Reader>(ParseReader(reader));
}

void FlutterZebraRfidWindows::ReaderConfig(
    std::function<void(ErrorOr<flutter_zebra_rfid::ReaderConfig> reply)> result) {
  const HelperResult helper_result = helper_->Send("readerConfig", "{}");
  if (!helper_result.ok) {
    result(ToFlutterError(helper_result));
    return;
  }
  const std::string data = JsonObject(helper_result.json, "data");
  result(ParseReaderConfig(JsonObject(data, "config")));
}

void FlutterZebraRfidWindows::SupportedReaderRegions(
    std::function<void(ErrorOr<flutter::EncodableList> reply)> result) {
  result(Unsupported("Reader region discovery"));
}

void FlutterZebraRfidWindows::SetReaderRegion(
    const std::string& region_code,
    std::function<void(std::optional<FlutterError> reply)> result) {
  result(Unsupported("Reader region configuration"));
}

void FlutterZebraRfidWindows::Diagnostics(
    std::function<void(ErrorOr<flutter_zebra_rfid::Diagnostics> reply)> result) {
  const HelperResult helper_result = helper_->Send("diagnostics", "{}");
  if (!helper_result.ok) {
    result(ToFlutterError(helper_result));
    return;
  }
  const std::string data = JsonObject(helper_result.json, "data");
  result(ParseDiagnostics(JsonObject(data, "diagnostics")));
}

void FlutterZebraRfidWindows::SetScanningEnabled(
    bool enabled,
    std::function<void(std::optional<FlutterError> reply)> result) {
  const HelperResult helper_result = helper_->Send(
      "setScanningEnabled", std::string("{\"enabled\":") + (enabled ? "true" : "false") + "}");
  result(helper_result.ok ? std::optional<FlutterError>()
                          : std::optional<FlutterError>(ToFlutterError(helper_result)));
}

void FlutterZebraRfidWindows::HandleHelperEvent(const std::string& json) {
  const std::string event = JsonString(json, "event").value_or("");
  const std::string data = JsonObject(json, "data");
  if (event == "readers") {
    callbacks_->OnAvailableReadersChanged(ParseReaders(data), IgnoreCallbackSuccess,
                                          IgnoreCallbackError);
  } else if (event == "status") {
    callbacks_->OnReaderConnectionStatusChanged(
        StatusFromString(JsonString(data, "status").value_or("disconnected")),
        IgnoreCallbackSuccess, IgnoreCallbackError);
  } else if (event == "tags") {
    callbacks_->OnTagsRead(ParseTags(data), IgnoreCallbackSuccess,
                           IgnoreCallbackError);
  } else if (event == "battery") {
    BatteryData battery(JsonInt(data, "level").value_or(0),
                        JsonBool(data, "isCharging").value_or(false),
                        JsonString(data, "cause").value_or("reader"));
    callbacks_->OnBatteryDataReceived(battery, IgnoreCallbackSuccess,
                                      IgnoreCallbackError);
  } else if (event == "error") {
    const std::string message = JsonString(data, "message").value_or("RFID helper failed.");
    const std::string details = JsonString(data, "details").value_or("");
    ReaderError error(ErrorCodeFromString(JsonString(data, "code").value_or("unknown")),
                      message, &details);
    callbacks_->OnReaderConnectionError(error, IgnoreCallbackSuccess,
                                        IgnoreCallbackError);
  }
}

}  // namespace flutter_zebra_rfid
