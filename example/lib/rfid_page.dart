import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid/shared_types.dart';

class RfidPage extends StatefulWidget {
  const RfidPage({super.key});

  @override
  State<RfidPage> createState() => _RfidPageState();
}

class _RfidPageState extends State<RfidPage> {
  final _flutterZebraRfidApi = FlutterZebraRfidApi();

  // Reader
  List<Reader> _availableReaders = [];
  List<RfidTag> _readTags = [];
  ConnectionStatus _connectionStatus = ConnectionStatus.disconnected;
  Reader? _currentReader;
  BatteryData? _batteryData;
  DateTime? _batteryLastUpdate;
  int _batteryUpdateCount = 0;
  ReaderError? _lastError;
  Diagnostics? _diagnostics;
  bool _scanningEnabled = true;
  bool _diagnosticsDialogOpen = false;

  ReaderConnectionType _connectionType = ReaderConnectionType.all;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();

    // RFID reader
    _flutterZebraRfidApi.onAvailableReadersChanged.listen((readers) async {
      final reader = await _flutterZebraRfidApi.currentReader;
      setState(() {
        _availableReaders = readers;
        _currentReader = reader;
      });
    });

    _flutterZebraRfidApi.onReaderConnectionStatusChanged.listen((status) async {
      final reader = await _flutterZebraRfidApi.currentReader;
      setState(() {
        _connectionStatus = status; // already a ConnectionStatus from wrapper
        _currentReader = reader;
        if (_connectionStatus == ConnectionStatus.connected) {
          // configure reader
          _flutterZebraRfidApi.configureReader(
              config: ReaderConfig(
                transmitPowerIndex: 299,
                beeperVolume: ReaderBeeperVolume.medium,
                enableDynamicPower: false,
                enableLedBlink: true,
                batchMode: ReaderConfigBatchMode.auto,
                scanBatchMode: ReaderConfigBatchMode.auto,
              ),
              shouldPersist: false);
          // Auto-exit diagnostics: clear last error & diagnostics snapshot if previously shown
          _lastError = null;
          _diagnostics = null;
          // Close diagnostics dialog if open
          if (_diagnosticsDialogOpen) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) {
                Navigator.of(context, rootNavigator: true).maybePop();
              }
              _diagnosticsDialogOpen = false;
            });
          }
        }
      });
    });

    _flutterZebraRfidApi.onTagsRead.listen(
      (tags) => setState(() => _readTags = tags),
    );

    _flutterZebraRfidApi.onBatteryDataReceived.listen((batteryData) {
      setState(() {
        _batteryData = batteryData;
        _batteryLastUpdate = DateTime.now();
        _batteryUpdateCount++;
      });
    });

    _flutterZebraRfidApi.onReaderConnectionError.listen((error) async {
      final d = await _flutterZebraRfidApi.diagnostics();
      setState(() {
        _lastError = error;
        _diagnostics = d;
        if (d.scanningEnabled != null) _scanningEnabled = d.scanningEnabled!;
      });
      _showDiagnosticsDialog();
    });
  }

  Future<void> _refreshDiagnostics({StateSetter? dialogSetState}) async {
    final d = await _flutterZebraRfidApi.diagnostics();
    if (!mounted) return;
    setState(() {
      _diagnostics = d;
      if (d.scanningEnabled != null) _scanningEnabled = d.scanningEnabled!;
    });
    dialogSetState?.call(() {}); // trigger rebuild inside dialog if provided
  }

  void _showDiagnosticsDialog() {
    if (_diagnosticsDialogOpen) return; // already shown
    if (_diagnostics == null) return; // nothing to show
    _diagnosticsDialogOpen = true;
    showDialog(
      context: context,
      barrierDismissible: true,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, dialogSetState) {
            final d = _diagnostics;
            return AlertDialog(
              title: const Text('Diagnostics'),
              content: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (d == null)
                      const Text('No data')
                    else ...[
                      Text('State: ${d.connectionState.name}'),
                      Text('Attempts: ${d.connectAttempts}'),
                      Text('Last Error Code: ${d.lastErrorCode?.name ?? '-'}'),
                      Text('Last Error Msg: ${d.lastErrorMessage ?? '-'}'),
                      Text(
                          'Last Connect Start: ${d.lastConnectStartMs ?? '-'}'),
                      Text(
                          'Last Connect Duration ms: ${d.lastConnectDurationMs ?? '-'}'),
                      Text('Locating: ${d.isLocating}'),
                      Text(
                          'Scanning Enabled: ${d.scanningEnabled ?? _scanningEnabled}'),
                    ],
                    const Divider(),
                    const Text('Battery',
                        style: TextStyle(fontWeight: FontWeight.bold)),
                    if (_batteryData == null)
                      const Text('No battery data')
                    else ...[
                      Text('Level: ${_batteryData!.level}%'),
                      // Raw battery debug line (shows unformatted underlying fields)
                      Text(
                          'Raw: {level: ${_batteryData!.level}, charging: ${_batteryData!.isCharging}, cause: ${_batteryData!.cause}}'),
                      Text('Charging: ${_batteryData!.isCharging}'),
                      Text('Cause: ${_batteryData!.cause}'),
                      Text(
                          'Last Update: ${_batteryLastUpdate?.toIso8601String() ?? '-'}'),
                      Text('Update Count: $_batteryUpdateCount'),
                    ],
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () =>
                      _refreshDiagnostics(dialogSetState: dialogSetState),
                  child: const Text('Refresh'),
                ),
                TextButton(
                  onPressed: () async {
                    final next = !_scanningEnabled;
                    await _flutterZebraRfidApi.setScanningEnabled(
                        enabled: next);
                    await _refreshDiagnostics(dialogSetState: dialogSetState);
                  },
                  child: Text(_scanningEnabled
                      ? 'Disable Scanning'
                      : 'Enable Scanning'),
                ),
                TextButton(
                  onPressed: () {
                    _flutterZebraRfidApi.triggerDeviceStatus();
                  },
                  child: const Text('Force Status'),
                ),
                TextButton(
                  onPressed: () {
                    Navigator.of(context).pop();
                    _diagnosticsDialogOpen = false;
                  },
                  child: const Text('Close'),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(() {
      _diagnosticsDialogOpen = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    // Make the container vertically flexible: diagnostics (if any) + scrollable list
    return Column(
      children: [
        Expanded(
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _ReadersContainer(
                  availableReaders: _availableReaders,
                  connectionStatus: _connectionStatus,
                  currentReader: _currentReader,
                  batteryData: _batteryData,
                  lastError: _lastError,
                  onConnect: (id) =>
                      _flutterZebraRfidApi.connectReader(readerId: id),
                  onDisconnect: () =>
                      _flutterZebraRfidApi.disconectCurrentReader(),
                  onStatus: () => _flutterZebraRfidApi.triggerDeviceStatus(),
                ),
        ),
        if (_readTags.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Column(
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 16),
                  child: Text('Read tags:'),
                ),
                ListView.separated(
                  shrinkWrap: true,
                  itemCount: _readTags.length,
                  itemBuilder: (context, index) {
                    final item = _readTags[index];
                    return Container(
                      color: Colors.white,
                      child: Row(
                        children: [
                          Expanded(
                            child: Container(
                              padding: const EdgeInsets.all(8),
                              child: Text(item.id),
                            ),
                          ),
                          Padding(
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 8),
                              child: Text(item.rssi.toString()))
                        ],
                      ),
                    );
                  },
                  separatorBuilder: (context, index) =>
                      Container(height: 1, color: Colors.grey),
                ),
              ],
            ),
          ),
        SizedBox(
          height: 60,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ConstrainedBox(
                  constraints:
                      const BoxConstraints(minWidth: 150, maxWidth: 240),
                  child: DropdownButton<ReaderConnectionType>(
                    value: _connectionType,
                    isExpanded: true,
                    items: const [
                      DropdownMenuItem(
                        value: ReaderConnectionType.all,
                        child: Text('All'),
                      ),
                      DropdownMenuItem(
                        value: ReaderConnectionType.usb,
                        child: Text('USB'),
                      ),
                      DropdownMenuItem(
                        value: ReaderConnectionType.bluetooth,
                        child: Text('Bluetooth'),
                      ),
                    ],
                    onChanged: (value) =>
                        setState(() => _connectionType = value!),
                  ),
                ),
                const SizedBox(width: 12),
                ElevatedButton(
                  onPressed: () async {
                    setState(() => _isLoading = true);
                    await _flutterZebraRfidApi.updateAvailableReaders(
                      connectionType: _connectionType,
                    );
                    setState(() => _isLoading = false);
                  },
                  child: const Text('Get Reader List'),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: () async {
                    final d = await _flutterZebraRfidApi.diagnostics();
                    setState(() => _diagnostics = d);
                    _showDiagnosticsDialog();
                  },
                  child: const Text('Show Diagnostics'),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _ReadersContainer extends StatelessWidget {
  const _ReadersContainer({
    required this.availableReaders,
    required this.connectionStatus,
    this.batteryData,
    this.currentReader,
    this.lastError,
    this.onConnect,
    this.onDisconnect,
    this.onStatus,
  });

  final List<Reader> availableReaders;
  final ConnectionStatus connectionStatus;
  final BatteryData? batteryData;
  final Reader? currentReader;
  final ReaderError? lastError;
  final Function(int)? onConnect;
  final VoidCallback? onDisconnect;
  final VoidCallback? onStatus;

  @override
  Widget build(BuildContext context) {
    Widget connectionStatusIcon() {
      switch (connectionStatus) {
        case ConnectionStatus.connecting:
        case ConnectionStatus.disconnecting:
          return const SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator());
        case ConnectionStatus.connected:
          return const Icon(Icons.wifi_outlined, color: Colors.green);
        case ConnectionStatus.disconnected:
          return const Icon(Icons.wifi_off_outlined, color: Colors.red);
        case ConnectionStatus.error:
          return const Icon(Icons.error_outline, color: Colors.orange);
      }
    }

    Widget batteryStatusIcon() {
      if (batteryData == null) return const Icon(Icons.battery_unknown);
      if (batteryData!.isCharging) {
        return const Icon(Icons.battery_charging_full, color: Colors.green);
      }
      final level = batteryData!.level;
      if (level == 0) {
        return const Icon(Icons.battery_0_bar);
      }
      if (level < 15) {
        return const Icon(Icons.battery_1_bar);
      }
      if (level < 30) {
        return const Icon(Icons.battery_2_bar);
      }
      if (level < 45) {
        return const Icon(Icons.battery_3_bar);
      }
      if (level < 60) {
        return const Icon(Icons.battery_4_bar);
      }
      if (level < 75) {
        return const Icon(Icons.battery_5_bar);
      }
      if (level < 90) {
        return const Icon(Icons.battery_6_bar);
      }
      return const Icon(Icons.battery_full);
    }

    if (availableReaders.isEmpty) {
      return const Center(
        child: Text('No RFID readers detected!'),
      );
    }
    return Column(
      children: [
        const Padding(
          padding: EdgeInsets.only(bottom: 16),
          child: Text('Detected readers'),
        ),
        if (lastError != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              color: Colors.red.shade50,
              child: Text(
                'Last Error: ${lastError!.code.name} - ${lastError!.message}',
                style: const TextStyle(color: Colors.red),
              ),
            ),
          ),
        // Diagnostics removed from inline view – use popup
        // Scrollable reader list (remaining space)
        Expanded(
          child: Container(
            decoration: BoxDecoration(border: Border.all(color: Colors.black)),
            child: ListView.separated(
              itemCount: availableReaders.length,
              itemBuilder: (context, index) {
                final item = availableReaders[index];
                final isCurrentItem = item.id == currentReader?.id;
        final isConnected = isCurrentItem &&
          connectionStatus == ConnectionStatus.connected;
                return Container(
                  color: Colors.white,
                  child: GestureDetector(
                    onTap: () {
            if (connectionStatus != ConnectionStatus.connecting &&
              connectionStatus != ConnectionStatus.disconnecting) {
                        showDialog(
                          context: context,
                          builder: (context) => Center(
                            child: Wrap(
                              children: [
                                Container(
                                  color: Colors.white,
                                  padding: const EdgeInsets.all(16),
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      const Text('Reader'),
                                      Text(item.name ?? item.id.toString()),
                                      Padding(
                                        padding: const EdgeInsets.only(top: 8),
                                        child: Wrap(
                                          children: [
                                            ElevatedButton(
                                              onPressed: () {
                                                if (isCurrentItem &&
                                                    isConnected) {
                                                  onDisconnect?.call();
                                                  Navigator.of(context).pop();
                                                } else {
                                                  onConnect?.call(item.id);
                                                  Navigator.of(context).pop();
                                                }
                                              },
                                              child: Text(
                                                  isCurrentItem && isConnected
                                                      ? 'Disconnect'
                                                      : 'Connect'),
                                            ),
                                            if (isCurrentItem && isConnected)
                                              Padding(
                                                padding: const EdgeInsets.only(
                                                    left: 8),
                                                child: ElevatedButton(
                                                  onPressed: () {
                                                    onStatus?.call();
                                                    Navigator.of(context).pop();
                                                  },
                                                  child: const Text('Status'),
                                                ),
                                              ),
                                          ],
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      }
                    },
                    child: Row(
                      children: [
                        Expanded(
                          child: Container(
                            padding: const EdgeInsets.all(8),
                            child: Text(item.name ?? item.id.toString()),
                          ),
                        ),
                        if (isCurrentItem) ...[
                          Padding(
                            padding: const EdgeInsets.only(left: 8),
                            child: connectionStatusIcon(),
                          ),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            child: batteryStatusIcon(),
                          ),
                        ]
                      ],
                    ),
                  ),
                );
              },
              separatorBuilder: (context, index) =>
                  Container(height: 1, color: Colors.grey),
            ),
          ),
        ),
      ],
    );
  }
}
