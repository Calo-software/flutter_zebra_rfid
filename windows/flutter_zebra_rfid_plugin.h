#ifndef FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_PLUGIN_H_
#define FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_PLUGIN_H_

#include <flutter/plugin_registrar_windows.h>

#include <memory>

namespace flutter_zebra_rfid {

class FlutterZebraRfidWindows;
class FlutterZebraBarcodeWindows;

class FlutterZebraRfidPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows* registrar);

  explicit FlutterZebraRfidPlugin(flutter::PluginRegistrarWindows* registrar);
  ~FlutterZebraRfidPlugin() override;

  FlutterZebraRfidPlugin(const FlutterZebraRfidPlugin&) = delete;
  FlutterZebraRfidPlugin& operator=(const FlutterZebraRfidPlugin&) = delete;

 private:
  std::unique_ptr<FlutterZebraRfidWindows> rfid_api_;
  std::unique_ptr<FlutterZebraBarcodeWindows> barcode_api_;
};

}  // namespace flutter_zebra_rfid

#endif  // FLUTTER_PLUGIN_FLUTTER_ZEBRA_RFID_PLUGIN_H_
