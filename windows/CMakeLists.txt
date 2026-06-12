# The Flutter tooling requires that developers have a version of Visual Studio
# installed that includes CMake 3.14 or later. You should not increase this
# version, as doing so will cause the plugin to fail to compile for some
# customers of the plugin.
cmake_minimum_required(VERSION 3.14)

# Project-level configuration.
set(PROJECT_NAME "flutter_zebra_rfid")
project(${PROJECT_NAME} LANGUAGES CXX)

# Explicitly opt in to modern CMake behaviors to avoid warnings with recent
# versions of CMake.
cmake_policy(VERSION 3.14...3.25)

# This value is used when generating builds using this plugin, so it must
# not be changed
set(PLUGIN_NAME "flutter_zebra_rfid_plugin")

# Any new source files that you add to the plugin should be added here.
list(APPEND PLUGIN_SOURCES
  "flutter_zebra_barcode.g.cpp"
  "flutter_zebra_barcode.g.h"
  "flutter_zebra_barcode_windows.cpp"
  "flutter_zebra_barcode_windows.h"
  "flutter_zebra_rfid.g.cpp"
  "flutter_zebra_rfid.g.h"
  "flutter_zebra_rfid_plugin.cpp"
  "flutter_zebra_rfid_plugin.h"
  "flutter_zebra_rfid_windows.cpp"
  "flutter_zebra_rfid_windows.h"
  "zebra_rfid_helper_bridge.cpp"
  "zebra_rfid_helper_bridge.h"
)

# Define the plugin library target. Its name must not be changed (see comment
# on PLUGIN_NAME above).
add_library(${PLUGIN_NAME} SHARED
  "include/flutter_zebra_rfid/flutter_zebra_rfid_plugin_c_api.h"
  "flutter_zebra_rfid_plugin_c_api.cpp"
  ${PLUGIN_SOURCES}
)

# Apply a standard set of build settings that are configured in the
# application-level CMakeLists.txt. This can be removed for plugins that want
# full control over build settings.
apply_standard_settings(${PLUGIN_NAME})

# Symbols are hidden by default to reduce the chance of accidental conflicts
# between plugins. This should not be removed; any symbols that should be
# exported should be explicitly exported with the FLUTTER_PLUGIN_EXPORT macro.
set_target_properties(${PLUGIN_NAME} PROPERTIES
  CXX_VISIBILITY_PRESET hidden)
target_compile_definitions(${PLUGIN_NAME} PRIVATE FLUTTER_PLUGIN_IMPL)

# Source include directories and library dependencies. Add any plugin-specific
# dependencies here.
target_include_directories(${PLUGIN_NAME} INTERFACE
  "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(${PLUGIN_NAME} PRIVATE flutter flutter_wrapper_plugin)

set(ZEBRA_RFID_HELPER_PROJECT
  "${CMAKE_CURRENT_SOURCE_DIR}/helper/FlutterZebraRfidWindowsHelper.csproj")
set(ZEBRA_RFID_HELPER_EXE
  "$<TARGET_FILE_DIR:${PLUGIN_NAME}>/FlutterZebraRfidWindowsHelper.exe")

if(CMAKE_VS_MSBUILD_COMMAND)
  add_custom_command(TARGET ${PLUGIN_NAME} PRE_BUILD
    COMMAND "${CMAKE_VS_MSBUILD_COMMAND}" "${ZEBRA_RFID_HELPER_PROJECT}"
      /p:Configuration=$<CONFIG>
      /p:Platform=AnyCPU
      /p:OutDir="$<TARGET_FILE_DIR:${PLUGIN_NAME}>\\"
    VERBATIM)
endif()

file(GLOB ZEBRA_RFID_WINDOWS_SDK_FILES
  "${CMAKE_CURRENT_SOURCE_DIR}/../Windows.RFID.SDK_3.0.32.35/sdk/*.dll"
  "${CMAKE_CURRENT_SOURCE_DIR}/../Windows.RFID.SDK_3.0.32.35/sdk/*.json")

# List of absolute paths to libraries that should be bundled with the plugin.
# This list could contain prebuilt libraries, or libraries created by an
# external build triggered from this build file.
set(flutter_zebra_rfid_bundled_libraries
  "${ZEBRA_RFID_HELPER_EXE}"
  ${ZEBRA_RFID_WINDOWS_SDK_FILES}
  PARENT_SCOPE
)
