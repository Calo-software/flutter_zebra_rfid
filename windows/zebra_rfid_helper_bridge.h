#ifndef FLUTTER_PLUGIN_ZEBRA_RFID_HELPER_BRIDGE_H_
#define FLUTTER_PLUGIN_ZEBRA_RFID_HELPER_BRIDGE_H_

#include <windows.h>

#include <condition_variable>
#include <functional>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace flutter_zebra_rfid {

struct HelperResult {
  bool ok = false;
  std::string json;
  std::string error_code;
  std::string error_message;
  std::string error_details;
};

class ZebraRfidHelperBridge {
 public:
  explicit ZebraRfidHelperBridge(std::function<void(const std::string&)> event_handler);
  ~ZebraRfidHelperBridge();

  ZebraRfidHelperBridge(const ZebraRfidHelperBridge&) = delete;
  ZebraRfidHelperBridge& operator=(const ZebraRfidHelperBridge&) = delete;

  HelperResult Send(const std::string& method, const std::string& args_json);
  void Stop();

 private:
  bool Start();
  void ReadLoop();
  void HandleLine(const std::string& line);
  std::wstring HelperPath() const;

  std::function<void(const std::string&)> event_handler_;
  std::mutex mutex_;
  std::condition_variable cv_;
  std::map<std::string, HelperResult> responses_;
  int next_id_ = 1;
  bool started_ = false;
  bool stopping_ = false;
  HANDLE process_ = nullptr;
  HANDLE child_stdin_write_ = nullptr;
  HANDLE child_stdout_read_ = nullptr;
  std::thread reader_thread_;
};

std::string JsonEscape(const std::string& value);
std::optional<std::string> JsonString(const std::string& json, const std::string& key);
std::optional<int64_t> JsonInt(const std::string& json, const std::string& key);
std::optional<bool> JsonBool(const std::string& json, const std::string& key);
std::string JsonObject(const std::string& json, const std::string& key);
std::string JsonArray(const std::string& json, const std::string& key);
std::vector<std::string> JsonObjects(const std::string& array_json);

}  // namespace flutter_zebra_rfid

#endif  // FLUTTER_PLUGIN_ZEBRA_RFID_HELPER_BRIDGE_H_
