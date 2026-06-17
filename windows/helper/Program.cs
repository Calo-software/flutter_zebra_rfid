using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.IO.Ports;
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
            Directory.SetCurrentDirectory(AppDomain.CurrentDomain.BaseDirectory);
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
                    case "connectReaderByIp":
                        ConnectReaderByIp(Value<string>(args, "host"), OptionalInt(args, "port"));
                        ReplyOk(id, new Dictionary<string, object> { { "reader", CurrentReaderSnapshot() } });
                        break;
                    case "configureReader":
                        ConfigureReader(args);
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    case "configureWifi":
                        ConfigureWifi(args);
                        ReplyOk(id, new Dictionary<string, object>());
                        break;
                    case "wifiStatus":
                        ReplyOk(id, new Dictionary<string, object> { { "status", WifiStatusSnapshot() } });
                        break;
                    case "networkConfig":
                        ReplyOk(id, new Dictionary<string, object> { { "config", NetworkConfigSnapshot() } });
                        break;
                    case "configureNetwork":
                        ConfigureNetwork(args);
                        ReplyOk(id, new Dictionary<string, object> { { "config", NetworkConfigSnapshot() } });
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

            var discovered = new List<object>();
            var errors = new List<Exception>();
            var includeUsb = connectionType == "usb" || connectionType == "all" || string.IsNullOrWhiteSpace(connectionType);
            var includeIp = connectionType == "ip" || connectionType == "all" || string.IsNullOrWhiteSpace(connectionType);

            if (includeUsb)
            {
                TryAddManagedReaders(discovered, errors, "USB");
                TryAddUsbDeviceReaders(discovered, errors);
                TryAddSerialPortReaders(discovered, errors);
            }

            if (includeIp)
            {
                TryAddManagedReaders(discovered, errors, "IP");
                TryAddIpDeviceReaders(discovered, errors);
            }

            _readerInfos.Clear();
            _readerInfos.AddRange(DeduplicateReaders(discovered));

            if (_readerInfos.Count == 0 && errors.Count > 0)
            {
                throw new InvalidOperationException(
                    "No Zebra RFID readers were discovered. Last SDK error: " + errors[errors.Count - 1].GetBaseException().Message,
                    errors[errors.Count - 1]);
            }

            SendEvent("readers", new Dictionary<string, object> { { "readers", ReaderSnapshots() } });
        }

        private void TryAddManagedReaders(List<object> readers, List<Exception> errors, string modeName)
        {
            try
            {
                foreach (var reader in GetManagedReaders(modeName))
                {
                    readers.Add(reader);
                }
            }
            catch (Exception ex)
            {
                if (!IsExpectedManagedIpDiscoveryFailure(modeName, ex))
                {
                    errors.Add(ex);
                }
                try
                {
                    foreach (var reader in GetDirectManagedReaders(modeName))
                    {
                        readers.Add(reader);
                    }
                }
                catch (Exception fallbackEx)
                {
                    errors.Add(fallbackEx);
                }
            }
        }

        private static bool IsExpectedManagedIpDiscoveryFailure(string modeName, Exception ex)
        {
            return string.Equals(modeName, "IP", StringComparison.OrdinalIgnoreCase) &&
                string.Equals(ex.GetBaseException().Message, "Value does not fall within the expected range.", StringComparison.OrdinalIgnoreCase);
        }

        private IEnumerable<object> GetManagedReaders(string modeName)
        {
            var sdkType = RequiredType("Symbol.RFID.SDK.RfidSdk, Symbol.RFID.SDK");
            var managementFactory = GetProperty(sdkType, null, "ReaderManagementServicesFactory");
            var modeType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderCommunicationMode, Symbol.RFID.SDK.Domain.Reader");
            var mode = Enum.Parse(modeType, modeName);
            var management = Invoke(managementFactory, "Create", mode);
            var searchType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderSearchOptions, Symbol.RFID.SDK.Domain.Reader");
            var allReaders = Enum.Parse(searchType, "AllReaders");
            var readers = Invoke(management, "GetReaders", allReaders) as IEnumerable;

            if (readers != null)
            {
                foreach (var reader in readers)
                {
                    yield return reader;
                }
            }
        }

        private IEnumerable<object> GetDirectManagedReaders(string modeName)
        {
            var managementTypeName = modeName == "IP"
                ? "Symbol.RFID.SDK.Domain.Reader.Infrastructure.Management.IPReaderManagement, Symbol.RFID.SDK.Domain.Reader.Infrastructure.Management"
                : "Symbol.RFID.SDK.Domain.Reader.Infrastructure.Management.UsbReaderManagement, Symbol.RFID.SDK.Domain.Reader.Infrastructure.Management";
            var management = Activator.CreateInstance(RequiredType(managementTypeName));
            var searchType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderSearchOptions, Symbol.RFID.SDK.Domain.Reader");
            var allReaders = Enum.Parse(searchType, "AllReaders");
            var readers = Invoke(management, "GetReaders", allReaders) as IEnumerable;

            if (readers != null)
            {
                foreach (var reader in readers)
                {
                    yield return reader;
                }
            }
        }

        private void TryAddUsbDeviceReaders(List<object> readers, List<Exception> errors)
        {
            try
            {
                var clientType = RequiredType("Symbol.RFID.SDK.USB.UsbDeviceClient, Symbol.RFID.SDK.USB");
                var client = Activator.CreateInstance(clientType);
                try
                {
                    var devices = Invoke(client, "DiscoverDevices") as IEnumerable;
                    if (devices == null)
                    {
                        return;
                    }

                    foreach (var device in devices)
                    {
                        var name = Convert.ToString(GetProperty(device, "DeviceName"));
                        var id = Convert.ToString(GetProperty(device, "DeviceIdentifier"));
                        var comPort = Convert.ToString(GetProperty(device, "COMPort"));
                        if (string.IsNullOrWhiteSpace(comPort))
                        {
                            continue;
                        }
                        readers.Add(CreateReaderInfo(
                            string.IsNullOrWhiteSpace(id) ? comPort : id,
                            string.IsNullOrWhiteSpace(name) ? comPort : name,
                            comPort,
                            "USB",
                            "RFD",
                            115200));
                    }
                }
                finally
                {
                    SafeInvoke(client, "Close");
                    SafeInvoke(client, "Dispose");
                }
            }
            catch (Exception ex)
            {
                errors.Add(ex);
            }
        }

        private void TryAddSerialPortReaders(List<object> readers, List<Exception> errors)
        {
            try
            {
                foreach (var comPort in SerialPort.GetPortNames()
                    .OrderByDescending(ComPortNumber)
                    .ThenByDescending(port => port, StringComparer.OrdinalIgnoreCase))
                {
                    readers.Add(CreateReaderInfo(
                        comPort,
                        "USB Serial Device (" + comPort + ")",
                        comPort,
                        "USB",
                        "RFD",
                        115200));
                }
            }
            catch (Exception ex)
            {
                errors.Add(ex);
            }
        }

        private void TryAddIpDeviceReaders(List<object> readers, List<Exception> errors)
        {
            try
            {
                var clientType = RequiredType("Symbol.RFID.SDK.IP.IPDeviceClient, Symbol.RFID.SDK.IP");
                var client = Activator.CreateInstance(clientType);
                try
                {
                    var devices = Invoke(client, "DiscoverDevices") as IEnumerable;
                    if (devices == null)
                    {
                        return;
                    }

                    foreach (var device in devices)
                    {
                        var name = Convert.ToString(GetProperty(device, "DeviceName"));
                        var id = Convert.ToString(GetProperty(device, "DeviceIdentifier"));
                        var port = Convert.ToString(GetProperty(device, "Port"));
                        readers.Add(CreateReaderInfo(
                            string.IsNullOrWhiteSpace(id) ? port : id,
                            string.IsNullOrWhiteSpace(name) ? id : name,
                            port,
                            "IP",
                            "FXP",
                            0));
                    }
                }
                finally
                {
                    SafeInvoke(client, "Close");
                    SafeInvoke(client, "Dispose");
                }
            }
            catch (Exception ex)
            {
                errors.Add(ex);
            }
        }

        private object CreateReaderInfo(string id, string friendlyName, string port, string communicationMode, string readerType, int baudRate)
        {
            var readerInfoType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderInfo, Symbol.RFID.SDK.Domain.Reader");
            var statusType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderStatus, Symbol.RFID.SDK.Domain.Reader");
            var modeType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderCommunicationMode, Symbol.RFID.SDK.Domain.Reader");
            var typeType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ReaderType, Symbol.RFID.SDK.Domain.Reader");
            return Activator.CreateInstance(
                readerInfoType,
                friendlyName ?? id ?? port ?? "Zebra RFID Reader",
                id ?? string.Empty,
                port ?? string.Empty,
                Enum.Parse(statusType, "NotConnected"),
                Enum.Parse(modeType, communicationMode),
                Enum.Parse(typeType, readerType),
                baudRate);
        }

        private object CreateIpReaderInfo(string host, int? port)
        {
            var portValue = port.HasValue && port.Value > 0 ? port.Value : 5084;
            return CreateReaderInfo(
                host,
                "IP Reader (" + host + ")",
                Convert.ToString(portValue),
                "IP",
                "RFD",
                0);
        }

        private IEnumerable<object> DeduplicateReaders(IEnumerable<object> readers)
        {
            var keys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var reader in readers)
            {
                var key = Convert.ToString(SafeGetProperty(reader, "ComPortNumber"));
                if (string.IsNullOrWhiteSpace(key))
                {
                    key = Convert.ToString(SafeGetProperty(reader, "PortNumber"));
                }
                if (string.IsNullOrWhiteSpace(key))
                {
                    key = Convert.ToString(SafeGetProperty(reader, "ID"));
                }
                if (string.IsNullOrWhiteSpace(key) || keys.Add(key))
                {
                    yield return reader;
                }
            }
        }

        private static string ExtractComPort(string value)
        {
            if (string.IsNullOrWhiteSpace(value))
            {
                return null;
            }

            var marker = "(COM";
            var start = value.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
            if (start < 0)
            {
                return null;
            }

            start++;
            var end = value.IndexOf(')', start);
            if (end <= start)
            {
                return null;
            }

            return value.Substring(start, end - start);
        }

        private static int ComPortNumber(string port)
        {
            if (string.IsNullOrWhiteSpace(port) ||
                !port.StartsWith("COM", StringComparison.OrdinalIgnoreCase))
            {
                return 0;
            }

            int value;
            return int.TryParse(port.Substring(3), out value) ? value : 0;
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

            _currentReader = CreateReader(_currentReaderInfo);

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

        private void ConnectReaderByIp(string host, int? port)
        {
            if (string.IsNullOrWhiteSpace(host))
            {
                throw new ArgumentException("Reader IP address or host name is required.");
            }

            _connectAttempts++;
            _lastConnectStartMs = NowMs();
            SendStatus("connecting");

            SafeDisconnect(sendStatus: false);
            _currentReaderInfo = CreateIpReaderInfo(host.Trim(), port);
            _currentReader = CreateReader(_currentReaderInfo);

            SubscribeEvent(_currentReader, "BatteryStatusNotification", nameof(OnBatteryStatus));
            var inventory = GetProperty(_currentReader, "Inventory");
            SubscribeEvent(inventory, "TagDataReceived", nameof(OnTagDataReceived));

            Invoke(_currentReader, "Connect");
            _lastConnectDurationMs = NowMs() - _lastConnectStartMs;
            SendStatus("connected");

            if (_readerInfos.IndexOf(_currentReaderInfo) < 0)
            {
                _readerInfos.Add(_currentReaderInfo);
            }
            SendEvent("readers", new Dictionary<string, object> { { "readers", ReaderSnapshots() } });

            if (_scanningEnabled)
            {
                StartInventory();
            }
        }

        private object CreateReader(object readerInfo)
        {
            if (IsIpReaderInfo(readerInfo))
            {
                return CreateDirectIpReader(readerInfo);
            }

            var sdkType = RequiredType("Symbol.RFID.SDK.RfidSdk, Symbol.RFID.SDK");
            var readerFactory = GetProperty(sdkType, null, "RfidReaderFactory");
            try
            {
                return Invoke(readerFactory, "Create", readerInfo);
            }
            catch
            {
                if (IsUsbReaderInfo(readerInfo))
                {
                    return CreateDirectUsbReader(readerInfo);
                }
                if (IsIpReaderInfo(readerInfo))
                {
                    return CreateDirectIpReader(readerInfo);
                }
                throw;
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

        private void ConfigureWifi(Dictionary<string, object> args)
        {
            if (_currentReader == null)
            {
                throw new InvalidOperationException("Connect a reader over USB before configuring Wi-Fi.");
            }

            var config = args != null && args.ContainsKey("config") ? args["config"] as Dictionary<string, object> : null;
            if (config == null)
            {
                throw new ArgumentException("Wi-Fi config is required.");
            }

            var ssid = Value<string>(config, "ssid");
            if (string.IsNullOrWhiteSpace(ssid))
            {
                throw new ArgumentException("Wi-Fi SSID is required.");
            }

            var security = Value<string>(config, "security") ?? "wpaPersonal";
            var password = Value<string>(config, "password");
            var persist = !config.ContainsKey("persist") || Convert.ToBoolean(config["persist"]);
            var connectAfterSave = !config.ContainsKey("connectAfterSave") || Convert.ToBoolean(config["connectAfterSave"]);

            if (string.Equals(security, "wpaPersonal", StringComparison.OrdinalIgnoreCase) &&
                string.IsNullOrEmpty(password))
            {
                throw new ArgumentException("Wi-Fi password is required for WPA/WPA2 personal networks.");
            }

            StopInventory("wifi configuration");

            var configurations = GetProperty(_currentReader, "Configurations");
            var wpa = GetProperty(configurations, "WPAConfiguration");
            Invoke(wpa, "Enable");

            if (string.Equals(security, "open", StringComparison.OrdinalIgnoreCase))
            {
                Invoke(wpa, "Add", ssid, persist);
            }
            else
            {
                Invoke(wpa, "Add", ssid, password, persist);
            }

            Invoke(wpa, "SetPreferredSsid", ssid);

            if (persist)
            {
                SafeInvoke(wpa, "Save");
            }

            if (connectAfterSave)
            {
                Invoke(wpa, "Connect", ssid);
            }
        }

        private void ConfigureNetworkDhcp(object configurations)
        {
            var networkConfig = GetProperty(configurations, "NetworkConfiguration");
            SetProperty(networkConfig, "IpAddress", "0.0.0.0");
            SetProperty(networkConfig, "NetMask", "0.0.0.0");
            SetProperty(networkConfig, "DNS", "0.0.0.0");
            SetProperty(networkConfig, "Gateway", "0.0.0.0");
            SetProperty(networkConfig, "DHCP", "enable");
            SafeInvoke(networkConfig, "SetNetworkConfiguration");
            SetProperty(configurations, "NetworkConfiguration", networkConfig);
        }

        private void ConfigureNetwork(Dictionary<string, object> args)
        {
            if (_currentReader == null)
            {
                throw new InvalidOperationException("Connect a reader before configuring network settings.");
            }

            StopInventory("network configuration");

            var configurations = GetProperty(_currentReader, "Configurations");
            var networkConfig = GetProperty(configurations, "NetworkConfiguration");
            var dhcp = !args.ContainsKey("dhcp") || Convert.ToBoolean(args["dhcp"]);
            SetProperty(networkConfig, "IpAddress", dhcp ? "0.0.0.0" : Value<string>(args, "ipAddress"));
            SetProperty(networkConfig, "NetMask", dhcp ? "0.0.0.0" : Value<string>(args, "netMask"));
            SetProperty(networkConfig, "DNS", dhcp ? "0.0.0.0" : Value<string>(args, "dns"));
            SetProperty(networkConfig, "Gateway", dhcp ? "0.0.0.0" : Value<string>(args, "gateway"));
            SetProperty(networkConfig, "DHCP", dhcp ? "enable" : "disable");
            Invoke(networkConfig, "SetNetworkConfiguration");
            SetProperty(configurations, "NetworkConfiguration", networkConfig);

            if (!args.ContainsKey("reconnectWifi") || Convert.ToBoolean(args["reconnectWifi"]))
            {
                var wpa = GetProperty(configurations, "WPAConfiguration");
                SafeInvoke(wpa, "Disconnect");
                Thread.Sleep(1000);
                var ssid = Value<string>(args, "ssid");
                if (string.IsNullOrWhiteSpace(ssid))
                {
                    ssid = Convert.ToString(FirstProperty(WifiStatusProperties(wpa), "PREFERREDSSID", "SSID"));
                }
                if (string.IsNullOrWhiteSpace(ssid))
                {
                    Invoke(wpa, "Connect");
                }
                else
                {
                    Invoke(wpa, "Connect", ssid);
                }
            }
        }

        private Dictionary<string, object> WifiStatusSnapshot()
        {
            var snapshot = new Dictionary<string, object>
            {
                { "status", null },
                { "ssid", null },
                { "ipAddress", null },
                { "macAddress", null },
                { "properties", new Dictionary<string, object>() },
            };

            if (_currentReader == null)
            {
                return snapshot;
            }

            var configurations = GetProperty(_currentReader, "Configurations");
            var wpa = GetProperty(configurations, "WPAConfiguration");
            var status = SafeInvokeWithResult(wpa, "GetWiFiStatus") ?? SafeGetProperty(wpa, "WiFiStatus");
            if (status == null)
            {
                return snapshot;
            }

            snapshot["status"] = Convert.ToString(SafeGetProperty(status, "Status"));

            var propertySnapshot = WifiStatusProperties(status);
            snapshot["properties"] = propertySnapshot;
            snapshot["ssid"] = FirstProperty(propertySnapshot, "ESSID", "SSID", "WLAN");
            snapshot["ipAddress"] = FirstProperty(propertySnapshot, "ADDRESS", "IP", "IPADDR", "IP_ADDRESS");
            snapshot["macAddress"] = FirstProperty(propertySnapshot, "MAC", "MAC_ADDRESS", "BSSID");
            return snapshot;
        }

        private Dictionary<string, object> WifiStatusProperties(object statusOrWpa)
        {
            var status = statusOrWpa;
            if (statusOrWpa != null && statusOrWpa.GetType().Name == "WPAConfiguration")
            {
                status = SafeInvokeWithResult(statusOrWpa, "GetWiFiStatus") ?? SafeGetProperty(statusOrWpa, "WiFiStatus");
            }

            var propertySnapshot = new Dictionary<string, object>(StringComparer.OrdinalIgnoreCase);
            var properties = SafeGetField(status, "Properties") as IEnumerable;
            if (properties != null)
            {
                foreach (var item in properties)
                {
                    var key = Convert.ToString(SafeGetProperty(item, "Key"));
                    if (string.IsNullOrWhiteSpace(key))
                    {
                        continue;
                    }
                    propertySnapshot[key] = Convert.ToString(SafeGetProperty(item, "Value"));
                }
            }
            return propertySnapshot;
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

        private Dictionary<string, object> NetworkConfigSnapshot()
        {
            var config = new Dictionary<string, object>
            {
                { "dhcp", null },
                { "ipAddress", null },
                { "netMask", null },
                { "dns", null },
                { "gateway", null },
            };

            if (_currentReader == null)
            {
                return config;
            }

            var configurations = GetProperty(_currentReader, "Configurations");
            var networkConfig = GetProperty(configurations, "NetworkConfiguration");
            config["dhcp"] = Convert.ToString(SafeGetProperty(networkConfig, "DHCP"));
            config["ipAddress"] = Convert.ToString(SafeGetProperty(networkConfig, "IpAddress"));
            config["netMask"] = Convert.ToString(SafeGetProperty(networkConfig, "NetMask"));
            config["dns"] = Convert.ToString(SafeGetProperty(networkConfig, "DNS"));
            config["gateway"] = Convert.ToString(SafeGetProperty(networkConfig, "Gateway"));
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

        private static void SetProperty(object target, string name, object value)
        {
            target.GetType().GetProperty(name)?.SetValue(target, value);
        }

        private static object SafeGetField(object target, string name)
        {
            if (target == null)
            {
                return null;
            }

            try
            {
                return target.GetType().GetField(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetValue(target);
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

        private static void SafeInvoke(object target, string method)
        {
            if (target == null)
            {
                return;
            }

            try
            {
                Invoke(target, method);
            }
            catch
            {
                // Cleanup best-effort only.
            }
        }

        private static object SafeInvokeWithResult(object target, string method)
        {
            if (target == null)
            {
                return null;
            }

            try
            {
                return Invoke(target, method);
            }
            catch
            {
                return null;
            }
        }

        private static object FirstProperty(Dictionary<string, object> values, params string[] keys)
        {
            foreach (var key in keys)
            {
                object value;
                if (values.TryGetValue(key, out value) && value != null && !string.IsNullOrWhiteSpace(Convert.ToString(value)))
                {
                    return value;
                }
            }
            return null;
        }

        private object CreateDirectUsbReader(object readerInfo)
        {
            var connectionType = RequiredType(
                "Symbol.RFID.SDK.Connectivity.Windows.UsbSerialPortDeviceConnection, Symbol.RFID.SDK.Connectivity.Windows");
            var adapterType = RequiredType(
                "Symbol.RFID.SDK.Domain.Reader.Infrastructure.ZetiRfidReaderAdapter, Symbol.RFID.SDK.Domain.Reader.Infrastructure");
            var readerType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ZetiRfidReader, Symbol.RFID.SDK.Domain.Reader");
            var connection = Activator.CreateInstance(connectionType, readerInfo);
            var adapter = Activator.CreateInstance(adapterType, connection);
            return Activator.CreateInstance(readerType, readerInfo, adapter);
        }

        private object CreateDirectIpReader(object readerInfo)
        {
            var connectionType = RequiredType(
                "Symbol.RFID.SDK.Connectivity.Windows.IPSocketDeviceConnection, Symbol.RFID.SDK.Connectivity.Windows");
            var adapterType = RequiredType(
                "Symbol.RFID.SDK.Domain.Reader.Infrastructure.ZetiRfidReaderAdapter, Symbol.RFID.SDK.Domain.Reader.Infrastructure");
            var readerType = RequiredType("Symbol.RFID.SDK.Domain.Reader.ZetiRfidReader, Symbol.RFID.SDK.Domain.Reader");
            var connection = Activator.CreateInstance(connectionType, readerInfo);
            var adapter = Activator.CreateInstance(adapterType, connection);
            return Activator.CreateInstance(readerType, readerInfo, adapter);
        }

        private bool IsUsbReaderInfo(object readerInfo)
        {
            var communicationMode = Convert.ToString(SafeGetProperty(readerInfo, "CommunicationMode"));
            var comPort = Convert.ToString(SafeGetProperty(readerInfo, "ComPortNumber"));
            return string.Equals(communicationMode, "USB", StringComparison.OrdinalIgnoreCase) ||
                   !string.IsNullOrWhiteSpace(comPort);
        }

        private bool IsIpReaderInfo(object readerInfo)
        {
            var communicationMode = Convert.ToString(SafeGetProperty(readerInfo, "CommunicationMode"));
            return string.Equals(communicationMode, "IP", StringComparison.OrdinalIgnoreCase);
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

        private static int? OptionalInt(Dictionary<string, object> map, string key)
        {
            if (map == null || !map.ContainsKey(key) || map[key] == null)
            {
                return null;
            }
            return Convert.ToInt32(map[key]);
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
