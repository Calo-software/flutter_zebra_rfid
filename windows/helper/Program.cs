using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Web.Script.Serialization;

namespace FlutterZebraRfid.WindowsHelper
{
    internal sealed class Program
    {
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
        private static readonly object OutputLock = new object();

        private readonly List<object> _readerInfos = new List<object>();
        private object _currentReaderInfo;
        private object _currentReader;
        private bool _scanningEnabled = true;
        private bool _inventoryActive;
        private int _connectAttempts;
        private string _lastErrorCode;
        private string _lastErrorMessage;
        private long? _lastConnectStartMs;
        private long? _lastConnectDurationMs;
        private long? _lastInventoryStartMs;
        private long? _lastInventoryStopMs;
        private long? _scanningEnabledLastToggleMs;

        private static int Main()
        {
            AppDomain.CurrentDomain.AssemblyResolve += ResolveSdkAssembly;
            LoadSdkAssemblies();

            var program = new Program();
            string line;
            while ((line = Console.In.ReadLine()) != null)
            {
                if (string.IsNullOrWhiteSpace(line))
                {
                    continue;
                }

                program.HandleLine(line);
            }

            program.SafeDisconnect();
            return 0;
        }

        private void HandleLine(string line)
        {
            Dictionary<string, object> request = null;
            string id = null;
            try
            {
                request = Json.Deserialize<Dictionary<string, object>>(line);
                id = Value<string>(request, "id");
                var method = Value<string>(request, "method");
                var args = request.ContainsKey("args") ? request["args"] as Dictionary<string, object> : null;

                switch (method)
                {
                    case "discoverReaders":
                        DiscoverReaders(Value<string>(args, "connectionType"));
                        ReplyOk(id, new Dictionary<string, object> { { "readers", ReaderSnapshots() } });
                        break;
                    case "connectReader":
                        ConnectReader(Convert.ToInt32(Value<object>(args, "readerId")));
                        ReplyOk(id, new Dictionary<string, object> { { "reader", CurrentReaderSnapshot() } });
                        break;
                    case "configureReader":
                        ConfigureReader(args);
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    case "disconnectReader":
                        SafeDisconnect();
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    case "currentReader":
                        ReplyOk(id, new Dictionary<string, object> { { "reader", CurrentReaderSnapshot() } });
                        break;
                    case "readerConfig":
                        ReplyOk(id, new Dictionary<string, object> { { "config", ReaderConfigSnapshot() } });
                        break;
                    case "triggerDeviceStatus":
                        TriggerDeviceStatus();
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    case "diagnostics":
                        ReplyOk(id, new Dictionary<string, object> { { "diagnostics", DiagnosticsSnapshot() } });
                        break;
                    case "setScanningEnabled":
                        SetScanningEnabled(Convert.ToBoolean(Value<object>(args, "enabled")));
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    default:
                        ReplyError(id, "unsupported", "Unsupported helper method: " + method);
                        break;
                }
            }
            catch (Exception ex)
            {
                RecordError("sdkOperationFailure", ex);
                ReplyError(id, "sdkOperationFailure", ex.GetBaseException().Message, ex.ToString());
            }
        }

        private void DiscoverReaders(string connectionType)
        {
            if (connectionType == "bluetooth")
            {
                throw new NotSupportedException("Bluetooth discovery is not supported by the Windows RFID helper yet.");
            }

            var sdkType = RequiredType("Symbol.RFID.SDK.RfidSdk, Symbol.RFID.SDK");
            var managementFactory = GetProperty(sdkType, null, "ReaderManagementServicesFactory");
            var modeType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderCommunicationMode, Symbol.RFID.SDK.Domain.Reader");
            var usbMode = Enum.Parse(modeType, "USB");
            var management = Invoke(managementFactory, "Create", usbMode);
            var searchType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderSearchOptions, Symbol.RFID.SDK.Domain.Reader");
            var allReaders = Enum.Parse(searchType, "AllReaders");
            var readers = Invoke(management, "GetReaders", allReaders) as IEnumerable;

            _readerInfos.Clear();
            if (readers != null)
            {
                foreach (var reader in readers)
                {
                    _readerInfos.Add(reader);
                }
            }

            SendEvent("readers", new Dictionary<string, object> { { "readers", ReaderSnapshots() } });
        }

        private void ConnectReader(int readerId)
        {
            if (readerId < 0 || readerId >= _readerInfos.Count)
            {
                throw new InvalidOperationException("Reader index is out of range. Refresh readers and try again.");
            }

            _connectAttempts++;
            _lastConnectStartMs = NowMs();
            SendStatus("connecting");

            SafeDisconnect(sendStatus: false);
            _currentReaderInfo = _readerInfos[readerId];

            var sdkType = RequiredType("Symbol.RFID.SDK.RfidSdk, Symbol.RFID.SDK");
            var readerFactory = GetProperty(sdkType, null, "RfidReaderFactory");
            _currentReader = Invoke(readerFactory, "Create", _currentReaderInfo);

            SubscribeEvent(_currentReader, "BatteryStatusNotification", nameof(OnBatteryStatus));
            var inventory = GetProperty(_currentReader, "Inventory");
            SubscribeEvent(inventory, "TagDataReceived", nameof(OnTagDataReceived));

            Invoke(_currentReader, "Connect");
            _lastConnectDurationMs = NowMs() - _lastConnectStartMs;
            SendStatus("connected");
            SendEvent("readers", new Dictionary<string, object> { { "readers", ReaderSnapshots() } });

            if (_scanningEnabled)
            {
                StartInventory();
            }
        }

        private void ConfigureReader(Dictionary<string, object> args)
        {
            if (_currentReader == null)
            {
                return;
            }

            var config = args != null && args.ContainsKey("config") ? args["config"] as Dictionary<string, object> : null;
            if (config == null)
            {
                return;
            }

            try
            {
                var configurations = GetProperty(_currentReader, "Configurations");
                var antennaConfig = Invoke(configurations, "GetConfig", Convert.ToUInt16(1));
                SetOptionalUInt16(antennaConfig, "TransmitPowerIndex", config, "transmitPowerIndex");
                SetOptionalUInt32(antennaConfig, "Tari", config, "tari");
                Invoke(configurations, "SetConfig", antennaConfig, Convert.ToUInt16(1));
            }
            catch
            {
                // Reader models differ in how much configuration they expose.
                // Keep v1 Windows configuration best-effort so connection/read flows remain usable.
            }
        }

        private void TriggerDeviceStatus()
        {
            if (_currentReader == null)
            {
                return;
            }

            var configurations = GetProperty(_currentReader, "Configurations");
            Invoke(configurations, "GetDeviceStatus", true, true, true);
        }

        private void SetScanningEnabled(bool enabled)
        {
            _scanningEnabled = enabled;
            _scanningEnabledLastToggleMs = NowMs();
            if (_currentReader == null)
            {
                return;
            }

            if (enabled)
            {
                StartInventory();
            }
            else
            {
                StopInventory("scanning disabled");
            }
        }

        private void StartInventory()
        {
            if (_currentReader == null || _inventoryActive)
            {
                return;
            }

            var inventory = GetProperty(_currentReader, "Inventory");
            Invoke(inventory, "Perform");
            _inventoryActive = true;
            _lastInventoryStartMs = NowMs();
        }

        private void StopInventory(string reason)
        {
            if (_currentReader == null || !_inventoryActive)
            {
                return;
            }

            try
            {
                var inventory = GetProperty(_currentReader, "Inventory");
                Invoke(inventory, "Stop");
            }
            finally
            {
                _inventoryActive = false;
                _lastInventoryStopMs = NowMs();
            }
        }

        private void SafeDisconnect(bool sendStatus = true)
        {
            if (_currentReader == null)
            {
                return;
            }

            if (sendStatus)
            {
                SendStatus("disconnecting");
            }

            try
            {
                StopInventory("disconnect");
                Invoke(_currentReader, "Disconnect");
            }
            catch
            {
                // Disconnect should be idempotent from Flutter's perspective.
            }
            finally
            {
                _currentReader = null;
                _currentReaderInfo = null;
                if (sendStatus)
                {
                    SendStatus("disconnected");
                }
            }
        }

        public void OnTagDataReceived(object sender, EventArgs e)
        {
            if (!_scanningEnabled)
            {
                return;
            }

            var epc = Convert.ToString(GetProperty(e, "EPCId"));
            if (string.IsNullOrWhiteSpace(epc))
            {
                return;
            }

            var tag = new Dictionary<string, object>
            {
                { "id", epc },
                { "rssi", Convert.ToInt32(GetProperty(e, "RSSI")) },
            };
            SendEvent("tags", new Dictionary<string, object> { { "tags", new[] { tag } } });
        }

        public void OnBatteryStatus(object sender, EventArgs e)
        {
            SendEvent("battery", new Dictionary<string, object>
            {
                { "level", Convert.ToInt32(GetProperty(e, "Level")) },
                { "isCharging", Convert.ToBoolean(GetProperty(e, "Charging")) },
                { "cause", Convert.ToString(GetProperty(e, "Cause")) ?? "reader" },
            });
        }

        private object[] ReaderSnapshots()
        {
            return _readerInfos.Select((reader, index) => ReaderSnapshot(reader, index)).ToArray();
        }

        private Dictionary<string, object> ReaderSnapshot(object readerInfo, int id)
        {
            var name = Convert.ToString(GetProperty(readerInfo, "FriendlyName"));
            return new Dictionary<string, object>
            {
                { "id", id },
                { "name", string.IsNullOrWhiteSpace(name) ? Convert.ToString(GetProperty(readerInfo, "ID")) : name },
                { "info", new Dictionary<string, object>
                    {
                        { "transmitPowerLevels", new object[0] },
                        { "firmwareVersion", null },
                        { "modelVersion", Convert.ToString(GetProperty(readerInfo, "ReaderType")) },
                        { "scannerName", name },
                        { "serialNumber", Convert.ToString(GetProperty(readerInfo, "ID")) },
                    }
                },
            };
        }

        private Dictionary<string, object> CurrentReaderSnapshot()
        {
            if (_currentReader == null || _currentReaderInfo == null)
            {
                return null;
            }

            var readerId = _readerInfos.IndexOf(_currentReaderInfo);
            var snapshot = ReaderSnapshot(_currentReaderInfo, readerId < 0 ? 0 : readerId);
            var info = snapshot["info"] as Dictionary<string, object>;
            var capabilities = SafeGetProperty(_currentReader, "Capabilities");
            if (capabilities != null)
            {
                info["transmitPowerLevels"] = SafeGetProperty(capabilities, "TransmitPowerLevelValues") ?? new object[0];
                info["firmwareVersion"] = Convert.ToString(SafeGetProperty(capabilities, "FirmwareVersion") ?? SafeGetProperty(capabilities, "FirwareVersion"));
                info["modelVersion"] = Convert.ToString(SafeGetProperty(capabilities, "ModelName"));
                info["scannerName"] = Convert.ToString(SafeGetProperty(capabilities, "ScannerName")) ?? Convert.ToString(snapshot["name"]);
                info["serialNumber"] = Convert.ToString(SafeGetProperty(capabilities, "SerialNumber"));
            }

            return snapshot;
        }

        private Dictionary<string, object> ReaderConfigSnapshot()
        {
            var config = new Dictionary<string, object>
            {
                { "transmitPowerIndex", null },
                { "tari", null },
                { "beeperVolume", null },
                { "enableDynamicPower", null },
                { "enableLedBlink", null },
                { "batchMode", null },
                { "scanBatchMode", null },
                { "rfModeTableIndex", null },
                { "receiveSensitivityIndex", null },
            };

            if (_currentReader == null)
            {
                return config;
            }

            try
            {
                var configurations = GetProperty(_currentReader, "Configurations");
                var antennaConfig = Invoke(configurations, "GetConfig", Convert.ToUInt16(1));
                config["transmitPowerIndex"] = Convert.ToInt32(SafeGetProperty(antennaConfig, "TransmitPowerIndex"));
                config["tari"] = Convert.ToInt32(SafeGetProperty(antennaConfig, "Tari"));
            }
            catch
            {
                // Leave unsupported fields null.
            }

            return config;
        }

        private Dictionary<string, object> DiagnosticsSnapshot()
        {
            return new Dictionary<string, object>
            {
                { "connectionState", _currentReader == null ? "disconnected" : "connected" },
                { "connectAttempts", _connectAttempts },
                { "lastErrorCode", _lastErrorCode },
                { "lastErrorMessage", _lastErrorMessage },
                { "lastConnectStartMs", _lastConnectStartMs },
                { "lastConnectDurationMs", _lastConnectDurationMs },
                { "isLocating", false },
                { "scanningEnabled", _scanningEnabled },
                { "scanningEnabledLastToggleMs", _scanningEnabledLastToggleMs },
                { "inventoryActive", _inventoryActive },
                { "lastInventoryStartMs", _lastInventoryStartMs },
                { "lastInventoryStopMs", _lastInventoryStopMs },
                { "pendingPurgeActive", false },
                { "lastInventoryStopReason", null },
                { "lastInventoryStartReason", null },
            };
        }

        private void SubscribeEvent(object target, string eventName, string handlerName)
        {
            if (target == null)
            {
                return;
            }

            var eventInfo = target.GetType().GetEvent(eventName);
            if (eventInfo == null)
            {
                return;
            }

            var handler = Delegate.CreateDelegate(
                eventInfo.EventHandlerType,
                this,
                GetType().GetMethod(handlerName, BindingFlags.Instance | BindingFlags.Public));
            eventInfo.AddEventHandler(target, handler);
        }

        private static object GetProperty(Type type, object target, string name)
        {
            return type.GetProperty(name, BindingFlags.Public | BindingFlags.Static | BindingFlags.Instance)?.GetValue(target);
        }

        private static object GetProperty(object target, string name)
        {
            return target.GetType().GetProperty(name)?.GetValue(target);
        }

        private static object SafeGetProperty(object target, string name)
        {
            if (target == null)
            {
                return null;
            }

            try
            {
                return GetProperty(target, name);
            }
            catch
            {
                return null;
            }
        }

        private static object Invoke(object target, string method, params object[] args)
        {
            var methods = target.GetType().GetMethods().Where(m => m.Name == method && m.GetParameters().Length == args.Length);
            foreach (var candidate in methods)
            {
                try
                {
                    return candidate.Invoke(target, args);
                }
                catch (TargetInvocationException)
                {
                    throw;
                }
                catch
                {
                    // Try another overload.
                }
            }

            throw new MissingMethodException(target.GetType().FullName, method);
        }

        private static Type RequiredType(string typeName)
        {
            var type = Type.GetType(typeName);
            if (type == null)
            {
                throw new InvalidOperationException("Unable to load SDK type: " + typeName);
            }
            return type;
        }

        private static void SetOptionalUInt16(object target, string property, Dictionary<string, object> values, string key)
        {
            if (values.ContainsKey(key) && values[key] != null)
            {
                target.GetType().GetProperty(property)?.SetValue(target, Convert.ToUInt16(values[key]));
            }
        }

        private static void SetOptionalUInt32(object target, string property, Dictionary<string, object> values, string key)
        {
            if (values.ContainsKey(key) && values[key] != null)
            {
                target.GetType().GetProperty(property)?.SetValue(target, Convert.ToUInt32(values[key]));
            }
        }

        private void SendStatus(string status)
        {
            SendEvent("status", new Dictionary<string, object> { { "status", status } });
        }

        private void SendEvent(string eventName, Dictionary<string, object> data)
        {
            var payload = new Dictionary<string, object>
            {
                { "event", eventName },
                { "data", data },
            };
            WriteJson(payload);
        }

        private void ReplyOk(string id, Dictionary<string, object> data)
        {
            WriteJson(new Dictionary<string, object>
            {
                { "id", id },
                { "ok", true },
                { "data", data },
            });
        }

        private void ReplyError(string id, string code, string message, string details = null)
        {
            WriteJson(new Dictionary<string, object>
            {
                { "id", id },
                { "ok", false },
                { "error", new Dictionary<string, object>
                    {
                        { "code", code },
                        { "message", message },
                        { "details", details },
                    }
                },
            });
        }

        private static void WriteJson(Dictionary<string, object> payload)
        {
            lock (OutputLock)
            {
                Console.Out.WriteLine(Json.Serialize(payload));
                Console.Out.Flush();
            }
        }

        private void RecordError(string code, Exception ex)
        {
            _lastErrorCode = code;
            _lastErrorMessage = ex.GetBaseException().Message;
            SendStatus("error");
            SendEvent("error", new Dictionary<string, object>
            {
                { "code", code },
                { "message", _lastErrorMessage },
                { "details", ex.ToString() },
            });
        }

        private static T Value<T>(Dictionary<string, object> map, string key)
        {
            if (map == null || !map.ContainsKey(key) || map[key] == null)
            {
                return default(T);
            }
            return (T)Convert.ChangeType(map[key], typeof(T));
        }

        private static long NowMs()
        {
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }

        private static Assembly ResolveSdkAssembly(object sender, ResolveEventArgs args)
        {
            var name = new AssemblyName(args.Name).Name + ".dll";
            var path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, name);
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        }

        private static void LoadSdkAssemblies()
        {
            foreach (var dll in Directory.GetFiles(AppDomain.CurrentDomain.BaseDirectory, "*.dll"))
            {
                try
                {
                    Assembly.LoadFrom(dll);
                }
                catch
                {
                    // Non-.NET DLLs or already-loaded assemblies can be ignored.
                }
            }
        }
    }
}
