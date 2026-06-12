#include "include/flutter_zebra_rfid/flutter_zebra_rfid_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "flutter_zebra_rfid_plugin.h"

void FlutterZebraRfidPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  flutter_zebra_rfid::FlutterZebraRfidPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
