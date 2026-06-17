#include "zebra_rfid_helper_bridge.h"

#include <chrono>
#include <regex>
#include <sstream>
#include <vector>

namespace flutter_zebra_rfid {
namespace {

constexpr wchar_t kHelperExe[] = L"FlutterZebraRfidWindowsHelper.exe";

std::string WideToUtf8(const std::wstring& value) {
  if (value.empty()) {
    return "";
  }
  const int size = WideCharToMultiByte(CP_UTF8, 0, value.data(),
                                      static_cast<int>(value.size()), nullptr,
                                      0, nullptr, nullptr);
  std::string result(static_cast<size_t>(size), '\0');
  WideCharToMultiByte(CP_UTF8, 0, value.data(), static_cast<int>(value.size()),
                      result.data(), size, nullptr, nullptr);
  return result;
}

std::wstring CurrentExeDirectory() {
  std::vector<wchar_t> buffer(MAX_PATH);
  DWORD length = GetModuleFileNameW(nullptr, buffer.data(),
                                    static_cast<DWORD>(buffer.size()));
  while (length == buffer.size()) {
    buffer.resize(buffer.size() * 2);
    length = GetModuleFileNameW(nullptr, buffer.data(),
                                static_cast<DWORD>(buffer.size()));
  }
  std::wstring path(buffer.data(), length);
  const size_t slash = path.find_last_of(L"\\/");
  return slash == std::wstring::npos ? L"." : path.substr(0, slash);
}

std::optional<std::string> RegexGroup(const std::string& json,
                                      const std::string& pattern) {
  std::smatch match;
  if (!std::regex_search(json, match, std::regex(pattern)) || match.size() < 2) {
    return std::nullopt;
  }
  return match[1].str();
}

std::string JsonValueBody(const std::string& json, const std::string& key,
                          char open, char close) {
  const std::string marker = "\"" + key + "\":";
  const size_t marker_pos = json.find(marker);
  if (marker_pos == std::string::npos) {
    return "";
  }
  size_t pos = json.find(open, marker_pos + marker.size());
  if (pos == std::string::npos) {
    return "";
  }
  int depth = 0;
  bool in_string = false;
  bool escaped = false;
  for (size_t index = pos; index < json.size(); ++index) {
    const char current = json[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (current == '\\') {
      escaped = in_string;
      continue;
    }
    if (current == '"') {
      in_string = !in_string;
      continue;
    }
    if (in_string) {
      continue;
    }
    if (current == open) {
      ++depth;
    } else if (current == close) {
      --depth;
      if (depth == 0) {
        return json.substr(pos, index - pos + 1);
      }
    }
  }
  return "";
}

}  // namespace

ZebraRfidHelperBridge::ZebraRfidHelperBridge(
    std::function<void(const std::string&)> event_handler)
    : event_handler_(std::move(event_handler)) {}

ZebraRfidHelperBridge::~ZebraRfidHelperBridge() {
  Stop();
}

HelperResult ZebraRfidHelperBridge::Send(const std::string& method,
                                         const std::string& args_json) {
  if (!Start()) {
    return HelperResult{false, "", "helperUnavailable",
                        "Unable to start FlutterZebraRfidWindowsHelper.exe",
                        WideToUtf8(HelperPath())};
  }

  std::string id;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    id = std::to_string(next_id_++);
  }

  std::ostringstream payload;
  payload << "{\"id\":\"" << id << "\",\"method\":\"" << JsonEscape(method)
          << "\",\"args\":" << (args_json.empty() ? "{}" : args_json)
          << "}\n";
  const std::string message = payload.str();
  DWORD written = 0;
  if (!WriteFile(child_stdin_write_, message.data(),
                 static_cast<DWORD>(message.size()), &written, nullptr)) {
    return HelperResult{false, "", "helperWriteFailed",
                        "Failed to send command to RFID helper.", ""};
  }

  std::unique_lock<std::mutex> lock(mutex_);
  const bool received = cv_.wait_for(lock, std::chrono::seconds(30), [&] {
    return responses_.find(id) != responses_.end() || stopping_;
  });
  if (!received) {
    return HelperResult{false, "", "timeout",
                        "Timed out waiting for RFID helper response.", ""};
  }
  auto it = responses_.find(id);
  if (it == responses_.end()) {
    return HelperResult{false, "", "helperStopped",
                        "RFID helper stopped before replying.", ""};
  }
  HelperResult result = it->second;
  responses_.erase(it);
  return result;
}

void ZebraRfidHelperBridge::Stop() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) {
      return;
    }
    stopping_ = true;
  }
  cv_.notify_all();
  if (child_stdin_write_) {
    CloseHandle(child_stdin_write_);
    child_stdin_write_ = nullptr;
  }
  if (child_stdout_read_) {
    CloseHandle(child_stdout_read_);
    child_stdout_read_ = nullptr;
  }
  if (process_) {
    TerminateProcess(process_, 0);
    CloseHandle(process_);
    process_ = nullptr;
  }
  if (reader_thread_.joinable()) {
    reader_thread_.join();
  }
}

bool ZebraRfidHelperBridge::Start() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (started_) {
    return true;
  }

  SECURITY_ATTRIBUTES security_attributes{};
  security_attributes.nLength = sizeof(SECURITY_ATTRIBUTES);
  security_attributes.bInheritHandle = TRUE;

  HANDLE child_stdin_read = nullptr;
  HANDLE child_stdout_write = nullptr;
  if (!CreatePipe(&child_stdout_read_, &child_stdout_write, &security_attributes,
                  0)) {
    return false;
  }
  if (!SetHandleInformation(child_stdout_read_, HANDLE_FLAG_INHERIT, 0)) {
    return false;
  }
  if (!CreatePipe(&child_stdin_read, &child_stdin_write_, &security_attributes,
                  0)) {
    return false;
  }
  if (!SetHandleInformation(child_stdin_write_, HANDLE_FLAG_INHERIT, 0)) {
    return false;
  }

  STARTUPINFOW startup_info{};
  startup_info.cb = sizeof(startup_info);
  startup_info.hStdError = child_stdout_write;
  startup_info.hStdOutput = child_stdout_write;
  startup_info.hStdInput = child_stdin_read;
  startup_info.dwFlags |= STARTF_USESTDHANDLES;

  PROCESS_INFORMATION process_info{};
  std::wstring command = L"\"" + HelperPath() + L"\"";
  std::vector<wchar_t> mutable_command(command.begin(), command.end());
  mutable_command.push_back(L'\0');

  const bool created =
      CreateProcessW(nullptr, mutable_command.data(), nullptr, nullptr, TRUE,
                     CREATE_NO_WINDOW, nullptr, CurrentExeDirectory().c_str(),
                     &startup_info, &process_info) != 0;
  CloseHandle(child_stdin_read);
  CloseHandle(child_stdout_write);
  if (!created) {
    return false;
  }

  process_ = process_info.hProcess;
  CloseHandle(process_info.hThread);
  started_ = true;
  stopping_ = false;
  responses_.clear();
  reader_thread_ = std::thread([this] { ReadLoop(); });
  return true;
}

void ZebraRfidHelperBridge::ReadLoop() {
  std::string buffer;
  char chunk[512];
  DWORD bytes_read = 0;
  while (child_stdout_read_ &&
         ReadFile(child_stdout_read_, chunk, sizeof(chunk), &bytes_read,
                  nullptr) &&
         bytes_read > 0) {
    buffer.append(chunk, chunk + bytes_read);
    size_t newline = std::string::npos;
    while ((newline = buffer.find('\n')) != std::string::npos) {
      std::string line = buffer.substr(0, newline);
      if (!line.empty() && line.back() == '\r') {
        line.pop_back();
      }
      buffer.erase(0, newline + 1);
      if (!line.empty()) {
        HandleLine(line);
      }
    }
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    stopping_ = true;
  }
  cv_.notify_all();
}

void ZebraRfidHelperBridge::HandleLine(const std::string& line) {
  const auto event = JsonString(line, "event");
  if (event.has_value()) {
    event_handler_(line);
    return;
  }

  const auto id = JsonString(line, "id");
  if (!id.has_value()) {
    return;
  }

  HelperResult result;
  result.json = line;
  result.ok = JsonBool(line, "ok").value_or(false);
  if (!result.ok) {
    const std::string error = JsonObject(line, "error");
    result.error_code = JsonString(error, "code").value_or("unknown");
    result.error_message = JsonString(error, "message").value_or("RFID helper failed.");
    result.error_details = "";
  }

  {
    std::lock_guard<std::mutex> lock(mutex_);
    responses_[*id] = result;
  }
  cv_.notify_all();
}

std::wstring ZebraRfidHelperBridge::HelperPath() const {
  return CurrentExeDirectory() + L"\\" + kHelperExe;
}

std::string JsonEscape(const std::string& value) {
  std::ostringstream escaped;
  for (const char c : value) {
    switch (c) {
      case '\\':
        escaped << "\\\\";
        break;
      case '"':
        escaped << "\\\"";
        break;
      case '\n':
        escaped << "\\n";
        break;
      case '\r':
        escaped << "\\r";
        break;
      case '\t':
        escaped << "\\t";
        break;
      default:
        escaped << c;
        break;
    }
  }
  return escaped.str();
}

std::optional<std::string> JsonString(const std::string& json,
                                      const std::string& key) {
  auto value = RegexGroup(json, "\"" + key + "\":\"((?:\\\\.|[^\"])*)\"");
  if (!value.has_value()) {
    return std::nullopt;
  }
  std::string unescaped;
  bool escaped = false;
  for (const char c : *value) {
    if (escaped) {
      unescaped.push_back(c);
      escaped = false;
    } else if (c == '\\') {
      escaped = true;
    } else {
      unescaped.push_back(c);
    }
  }
  return unescaped;
}

std::optional<int64_t> JsonInt(const std::string& json,
                               const std::string& key) {
  auto value = RegexGroup(json, "\"" + key + "\":(-?[0-9]+)");
  return value.has_value() ? std::optional<int64_t>(std::stoll(*value))
                           : std::nullopt;
}

std::optional<bool> JsonBool(const std::string& json,
                             const std::string& key) {
  auto value = RegexGroup(json, "\"" + key + "\":(true|false)");
  return value.has_value() ? std::optional<bool>(*value == "true")
                           : std::nullopt;
}

std::string JsonObject(const std::string& json, const std::string& key) {
  return JsonValueBody(json, key, '{', '}');
}

std::string JsonArray(const std::string& json, const std::string& key) {
  return JsonValueBody(json, key, '[', ']');
}

std::vector<std::string> JsonObjects(const std::string& array_json) {
  std::vector<std::string> objects;
  int depth = 0;
  bool in_string = false;
  bool escaped = false;
  size_t start = std::string::npos;
  for (size_t index = 0; index < array_json.size(); ++index) {
    const char current = array_json[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (current == '\\') {
      escaped = in_string;
      continue;
    }
    if (current == '"') {
      in_string = !in_string;
      continue;
    }
    if (in_string) {
      continue;
    }
    if (current == '{') {
      if (depth == 0) {
        start = index;
      }
      ++depth;
    } else if (current == '}') {
      --depth;
      if (depth == 0 && start != std::string::npos) {
        objects.push_back(array_json.substr(start, index - start + 1));
        start = std::string::npos;
      }
    }
  }
  return objects;
}

}  // namespace flutter_zebra_rfid
