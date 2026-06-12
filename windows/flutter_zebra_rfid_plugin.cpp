#include "flutter_zebra_rfid_plugin.h"

#include "flutter_zebra_barcode_windows.h"
#include "flutter_zebra_rfid.g.h"
#include "flutter_zebra_rfid_windows.h"

namespace flutter_zebra_rfid {

void FlutterZebraRfidPlugin::RegisterWithRegistrar(
    flutter::PluginRegistrarWindows* registrar) {
  auto plugin = std::make_unique<FlutterZebraRfidPlugin>(registrar);
  registrar->AddPlugin(std::move(plugin));
}

FlutterZebraRfidPlugin::FlutterZebraRfidPlugin(
    flutter::PluginRegistrarWindows* registrar) {
  auto* messenger = registrar->messenger();
  rfid_api_ = std::make_unique<FlutterZebraRfidWindows>(messenger);
  barcode_api_ = std::make_unique<FlutterZebraBarcodeWindows>();
  FlutterZebraRfid::SetUp(messenger, rfid_api_.get());
  SetupFlutterZebraBarcodeWindows(messenger, barcode_api_.get());
}

FlutterZebraRfidPlugin::~FlutterZebraRfidPlugin() = default;

}  // namespace flutter_zebra_rfid
