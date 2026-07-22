import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:flutter_zebra_rfid_example/example_ui.dart';

class RfidPage extends StatefulWidget {
  const RfidPage({super.key});

  @override
  State<RfidPage> createState() => _RfidPageState();
}

class _RfidPageState extends State<RfidPage>
    with AutomaticKeepAliveClientMixin<RfidPage> {
  @override
  bool get wantKeepAlive => true;
  final _flutterZebraRfidApi = FlutterZebraRfidApi();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  static const _logTag = 'RfidPage';
  static const _bluetoothScanDurationSeconds = 15;
  static const _bluetoothStopUnlockSeconds = 5;
  static const _maxReadTags = 200;

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
  final TextEditingController _bluetoothSearchController =
      TextEditingController();
  bool _scanningEnabled = true;
  bool _diagnosticsDialogOpen = false;
  final Map<String, BluetoothDevice> _bluetoothDevicesByAddress = {};
  BluetoothScanStatus? _bluetoothScanStatus;
  String? _pairingDeviceAddress;
  StateSetter? _bluetoothDialogSetState;
  Timer? _bluetoothScanTimer;
  int _bluetoothScanSecondsRemaining = 0;
  bool _bluetoothStopRequested = false;
  String _bluetoothSearchQuery = '';
  bool _bluetoothLikelyZebraOnly = true;
  bool _bluetoothOnlyUnpaired = false;

  ReaderConnectionType _connectionType = ReaderConnectionType.all;
  bool _isLoading = false;

  bool get _canConfigureRegion =>
      _isRegionConfigurationError(_lastError) || _currentReader != null;

  bool get _isBluetoothScanRunning =>
      _bluetoothScanStatus == BluetoothScanStatus.scanning &&
      _bluetoothScanSecondsRemaining > 0;

  bool get _canStopBluetoothScan =>
      _isBluetoothScanRunning &&
      _bluetoothScanSecondsRemaining <=
          _bluetoothScanDurationSeconds - _bluetoothStopUnlockSeconds;

  double get _bluetoothScanProgress {
    if (_bluetoothScanSecondsRemaining <= 0) {
      return 0;
    }
    final elapsed =
        _bluetoothScanDurationSeconds - _bluetoothScanSecondsRemaining;
    return elapsed / _bluetoothScanDurationSeconds;
  }

  @override
  void initState() {
    super.initState();

    // RFID reader
    _subscriptions
      ..add(_flutterZebraRfidApi.onAvailableReadersChanged
          .listen((readers) async {
        final reader = await _flutterZebraRfidApi.currentReader;
        if (!mounted) return;
        setState(() {
          _availableReaders = readers;
          _currentReader = reader;
        });
      }))
      ..add(_flutterZebraRfidApi.onReaderConnectionStatusChanged
          .listen((status) async {
        final reader = await _flutterZebraRfidApi.currentReader;
        if (!mounted) return;
        setState(() {
          _connectionStatus = status; // already a ConnectionStatus from wrapper
          _currentReader = reader;
          if (_connectionStatus == ConnectionStatus.disconnected) {
            _batteryData = null;
            _batteryLastUpdate = null;
            _batteryUpdateCount = 0;
          }
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
          } else if (_connectionStatus == ConnectionStatus.disconnected) {
            _lastError = null;
            _diagnostics = null;
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
      }))
      ..add(_flutterZebraRfidApi.onTagsRead.listen((tags) {
        if (!mounted) return;
        setState(() {
          _readTags = _mergeReadTags(_readTags, tags);
        });
      }))
      ..add(_flutterZebraRfidApi.onBatteryDataReceived.listen((batteryData) {
        if (!mounted) return;
        setState(() {
          _batteryData = batteryData;
          _batteryLastUpdate = DateTime.now();
          _batteryUpdateCount++;
        });
      }))
      ..add(_flutterZebraRfidApi.onBluetoothDeviceDiscovered.listen((device) {
        debugPrint(
          'D/$_logTag: onBluetoothDeviceDiscovered name=${device.name ?? '<unnamed>'} address=${device.address} paired=${device.isPaired}',
        );
        if (!mounted) return;
        _upsertBluetoothDevice(device);
      }))
      ..add(_flutterZebraRfidApi.onBluetoothScanStatusChanged.listen((status) {
        debugPrint(
            'D/$_logTag: onBluetoothScanStatusChanged status=${status.name}');
        if (!mounted) return;
        setState(() {
          _bluetoothScanStatus = status;
          if (status != BluetoothScanStatus.scanning) {
            _cancelBluetoothScanTimer();
            _bluetoothScanSecondsRemaining = 0;
          }
        });
        if (status == BluetoothScanStatus.finished) {
          final completionType = _bluetoothStopRequested ? 'manual' : 'natural';
          debugPrint(
            'D/$_logTag: Bluetooth scan finished via $completionType completion',
          );
          _bluetoothStopRequested = false;
        }
        if (status == BluetoothScanStatus.error) {
          debugPrint('E/$_logTag: Bluetooth scan ended with error status');
          _bluetoothStopRequested = false;
        }
        _notifyBluetoothDialog();
      }))
      ..add(
          _flutterZebraRfidApi.onBluetoothPairingResult.listen((result) async {
        debugPrint(
          'D/$_logTag: onBluetoothPairingResult success=${result.success} name=${result.device.name ?? '<unnamed>'} address=${result.device.address}',
        );
        if (!mounted) return;
        _upsertBluetoothDevice(result.device);
        if (!mounted) return;
        setState(() {
          _pairingDeviceAddress = null;
        });
        if (result.success) {
          await _loadBondedBluetoothDevices();
        }
        if (!mounted) return;
        _showMessage(
          result.success
              ? 'Paired ${result.device.name ?? result.device.address}. Refresh reader list to connect.'
              : 'Pairing failed for ${result.device.name ?? result.device.address}.',
        );
        _notifyBluetoothDialog();
      }))
      ..add(_flutterZebraRfidApi.onReaderConnectionError.listen((error) async {
        final reader = await _flutterZebraRfidApi.currentReader;
        final d = await _flutterZebraRfidApi.diagnostics();
        if (!mounted) return;
        setState(() {
          _lastError = error;
          _diagnostics = d;
          _currentReader = reader;
          if (d.scanningEnabled != null) _scanningEnabled = d.scanningEnabled!;
        });
        if (_isRegionConfigurationError(error)) {
          _showMessage(
              'Reader region not configured — select a region to continue.');
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _openReaderRegionDialog();
          });
        } else {
          _showMessage(error.message);
          _showDiagnosticsDialog();
        }
      }));

    // Auto-discover readers on load (matches barcode page behaviour).
    _flutterZebraRfidApi.updateAvailableReaders(
        connectionType: _connectionType);
  }

  @override
  void dispose() {
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    _cancelBluetoothScanTimer();
    _bluetoothSearchController.dispose();
    super.dispose();
  }

  List<BluetoothDevice> get _bluetoothDevices {
    final devices = _bluetoothDevicesByAddress.values.toList();
    devices.sort((left, right) {
      final leftZebra = _isLikelyZebraDevice(left);
      final rightZebra = _isLikelyZebraDevice(right);
      if (leftZebra != rightZebra) {
        return leftZebra ? -1 : 1;
      }
      if (left.isPaired != right.isPaired) {
        return left.isPaired ? -1 : 1;
      }
      return (left.name ?? left.address)
          .toLowerCase()
          .compareTo((right.name ?? right.address).toLowerCase());
    });
    return devices;
  }

  List<BluetoothDevice> get _filteredBluetoothDevices {
    final query = _bluetoothSearchQuery.trim().toLowerCase();
    return _bluetoothDevices.where((device) {
      if (_bluetoothLikelyZebraOnly && !_isLikelyZebraDevice(device)) {
        return false;
      }
      if (_bluetoothOnlyUnpaired && device.isPaired) {
        return false;
      }
      if (query.isEmpty) {
        return true;
      }
      final name = device.name?.toLowerCase() ?? '';
      final address = device.address.toLowerCase();
      return name.contains(query) || address.contains(query);
    }).toList();
  }

  bool _isLikelyZebraDevice(BluetoothDevice device) {
    final name = device.name?.toUpperCase() ?? '';
    return name.startsWith('RFD') ||
        name.startsWith('MC') ||
        name.startsWith('TC');
  }

  void _notifyBluetoothDialog() {
    _bluetoothDialogSetState?.call(() {});
  }

  void _resetBluetoothFilters() {
    _bluetoothSearchQuery = '';
    _bluetoothSearchController.text = '';
    _bluetoothLikelyZebraOnly = true;
    _bluetoothOnlyUnpaired = false;
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  bool _isRegionConfigurationError(ReaderError? error) {
    if (error == null) return false;
    final haystack = '${error.message} ${error.details ?? ''}'.toLowerCase();
    return haystack.contains('region') &&
        haystack.contains('supported regions');
  }

  String _readerRegionLabel(ReaderRegion region) {
    final standardName = region.standardName?.trim();
    if (standardName == null || standardName.isEmpty) {
      return region.code;
    }
    return '${region.code} ($standardName)';
  }

  Future<void> _openReaderRegionDialog() async {
    List<ReaderRegion> regions;
    try {
      regions = await _flutterZebraRfidApi.supportedReaderRegions();
    } catch (error) {
      _showMessage('Unable to load reader regions: $error');
      return;
    }

    if (!mounted) return;
    if (regions.isEmpty) {
      _showMessage(
          'No supported regions were reported for the selected reader.');
      return;
    }

    String selectedRegionCode = regions.first.code;
    bool isApplying = false;

    await showDialog<void>(
      context: context,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, dialogSetState) {
            return AlertDialog(
              title: const Text('Set Reader Region'),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Select the regulatory region to apply to the currently selected reader.',
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: selectedRegionCode,
                    isExpanded: true,
                    items: regions
                        .map(
                          (region) => DropdownMenuItem<String>(
                            value: region.code,
                            child: Text(_readerRegionLabel(region)),
                          ),
                        )
                        .toList(),
                    onChanged: isApplying
                        ? null
                        : (value) {
                            if (value == null) return;
                            dialogSetState(() {
                              selectedRegionCode = value;
                            });
                          },
                  ),
                ],
              ),
              actions: [
                TextButton(
                  onPressed:
                      isApplying ? null : () => Navigator.of(context).pop(),
                  child: const Text('Cancel'),
                ),
                ElevatedButton(
                  onPressed: isApplying
                      ? null
                      : () async {
                          final navigator = Navigator.of(context);
                          dialogSetState(() {
                            isApplying = true;
                          });
                          try {
                            await _flutterZebraRfidApi.setReaderRegion(
                              regionCode: selectedRegionCode,
                            );
                            if (!mounted) return;
                            navigator.pop();
                            _showMessage(
                              'Applied reader region $selectedRegionCode.',
                            );
                          } catch (error) {
                            dialogSetState(() {
                              isApplying = false;
                            });
                            _showMessage(
                                'Failed to apply reader region: $error');
                          }
                        },
                  child: Text(isApplying ? 'Applying...' : 'Apply Region'),
                ),
              ],
            );
          },
        );
      },
    );
  }

  Future<void> _showBluetoothSettingsHelperDialog() async {
    if (!mounted) return;

    await showDialog<void>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('Pair In Settings'),
          content: const Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'If the reader does not appear here, pair it through Android Bluetooth settings first, then return and refresh bonded devices.',
              ),
              SizedBox(height: 12),
              Text(
                  '1. Wake the Zebra reader and make sure Bluetooth pairing mode is enabled.'),
              SizedBox(height: 8),
              Text(
                  '2. Open Android Settings > Connected devices > Pair new device.'),
              SizedBox(height: 8),
              Text('3. Pair the reader there.'),
              SizedBox(height: 8),
              Text(
                  '4. Return here and tap Refresh Bonded, then refresh the Zebra reader list.'),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Close'),
            ),
          ],
        );
      },
    );
  }

  void _upsertBluetoothDevice(BluetoothDevice device) {
    debugPrint(
      'D/$_logTag: _upsertBluetoothDevice name=${device.name ?? '<unnamed>'} address=${device.address} paired=${device.isPaired}',
    );
    if (!mounted) return;
    setState(() {
      _bluetoothDevicesByAddress[device.address] = device;
      debugPrint(
          'D/$_logTag: device map size=${_bluetoothDevicesByAddress.length}');
    });
    _notifyBluetoothDialog();
  }

  Future<void> _loadBondedBluetoothDevices() async {
    debugPrint('D/$_logTag: _loadBondedBluetoothDevices start');
    final devices = await _flutterZebraRfidApi.getBondedDevices();
    debugPrint(
        'D/$_logTag: _loadBondedBluetoothDevices got ${devices.length} device(s)');
    if (!mounted) return;
    setState(() {
      for (final device in devices) {
        _bluetoothDevicesByAddress[device.address] = device;
      }
      debugPrint(
          'D/$_logTag: device map size after bonded load=${_bluetoothDevicesByAddress.length}');
    });
    _notifyBluetoothDialog();
  }

  void _cancelBluetoothScanTimer() {
    _bluetoothScanTimer?.cancel();
    _bluetoothScanTimer = null;
  }

  Future<void> _startManagedBluetoothScan() async {
    debugPrint('D/$_logTag: _startManagedBluetoothScan invoked');
    _cancelBluetoothScanTimer();
    _bluetoothStopRequested = false;
    await _flutterZebraRfidApi.startBluetoothScan();
    if (!mounted) return;
    setState(() {
      _bluetoothScanSecondsRemaining = _bluetoothScanDurationSeconds;
      _bluetoothScanStatus = BluetoothScanStatus.scanning;
    });
    _notifyBluetoothDialog();
    _bluetoothScanTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      final next = _bluetoothScanSecondsRemaining - 1;
      setState(() {
        _bluetoothScanSecondsRemaining =
            next.clamp(0, _bluetoothScanDurationSeconds);
      });
      _notifyBluetoothDialog();
      if (next <= 0) {
        timer.cancel();
        debugPrint(
            'D/$_logTag: Auto scan window elapsed, stopping Bluetooth scan');
        _stopManagedBluetoothScan(userInitiated: false);
      }
    });
  }

  Future<void> _stopManagedBluetoothScan({required bool userInitiated}) async {
    if (!_isBluetoothScanRunning &&
        _bluetoothScanStatus != BluetoothScanStatus.scanning) {
      return;
    }
    _bluetoothStopRequested = userInitiated;
    _cancelBluetoothScanTimer();
    if (mounted) {
      setState(() {
        _bluetoothScanSecondsRemaining = 0;
      });
    }
    _notifyBluetoothDialog();
    await _flutterZebraRfidApi.stopBluetoothScan();
  }

  Future<void> _openBluetoothPairingDialog() async {
    _resetBluetoothFilters();
    try {
      await _loadBondedBluetoothDevices();
    } catch (error) {
      _showMessage('Unable to load bonded Bluetooth devices: $error');
    }
    if (!mounted) return;

    await showDialog<void>(
      context: context,
      barrierDismissible: true,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, dialogSetState) {
            _bluetoothDialogSetState = dialogSetState;
            final devices = _filteredBluetoothDevices;
            final totalDevices = _bluetoothDevices.length;
            final scanStatus = _bluetoothScanStatus?.name ?? 'idle';
            return AlertDialog(
              title: const Text('Pair Bluetooth Reader'),
              content: SizedBox(
                width: 520,
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        'Bonded Bluetooth devices are shown immediately. Scan adds nearby discoverable devices, then pair the reader before refreshing the Zebra reader list.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 8),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: Colors.orange.shade50,
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: Colors.orange.shade200),
                        ),
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Padding(
                              padding: EdgeInsets.only(top: 2, right: 8),
                              child: Icon(Icons.info_outline, size: 18),
                            ),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text(
                                    'Reader not visible?',
                                    style:
                                        TextStyle(fontWeight: FontWeight.w600),
                                  ),
                                  const SizedBox(height: 4),
                                  const Text(
                                    'Pair it in Android Bluetooth settings first, then come back and tap Refresh Bonded.',
                                  ),
                                  Align(
                                    alignment: Alignment.centerLeft,
                                    child: TextButton(
                                      onPressed:
                                          _showBluetoothSettingsHelperDialog,
                                      style: TextButton.styleFrom(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 0,
                                          vertical: 4,
                                        ),
                                        minimumSize: Size.zero,
                                        tapTargetSize:
                                            MaterialTapTargetSize.shrinkWrap,
                                      ),
                                      child: const Text(
                                          'Show Manual Pairing Steps'),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          ElevatedButton(
                            onPressed: _isBluetoothScanRunning
                                ? null
                                : () async {
                                    debugPrint(
                                        'D/$_logTag: Scan for Devices tapped');
                                    try {
                                      await _startManagedBluetoothScan();
                                    } catch (error) {
                                      debugPrint(
                                          'E/$_logTag: Bluetooth scan failed: $error');
                                      _showMessage(
                                          'Bluetooth scan failed: $error');
                                    }
                                  },
                            child: Text(
                              _isBluetoothScanRunning
                                  ? 'Scanning...'
                                  : 'Scan for Devices',
                            ),
                          ),
                          ElevatedButton(
                            onPressed: _canStopBluetoothScan
                                ? () async {
                                    try {
                                      await _stopManagedBluetoothScan(
                                        userInitiated: true,
                                      );
                                    } catch (error) {
                                      _showMessage(
                                          'Failed to stop Bluetooth scan: $error');
                                    }
                                  }
                                : null,
                            child: const Text('Stop Scan'),
                          ),
                          TextButton(
                            onPressed: () async {
                              try {
                                await _loadBondedBluetoothDevices();
                              } catch (error) {
                                _showMessage(
                                    'Failed to refresh bonded devices: $error');
                              }
                            },
                            child: const Text('Refresh Bonded'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text('Scan status: $scanStatus'),
                      const SizedBox(height: 4),
                      Text(
                          'Showing ${devices.length} of $totalDevices devices'),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _bluetoothSearchController,
                        onChanged: (value) {
                          setState(() {
                            _bluetoothSearchQuery = value;
                          });
                          _notifyBluetoothDialog();
                        },
                        decoration: InputDecoration(
                          isDense: true,
                          hintText: 'Search name or address',
                          prefixIcon: const Icon(Icons.search, size: 18),
                          suffixIcon: _bluetoothSearchQuery.isEmpty
                              ? null
                              : IconButton(
                                  onPressed: () {
                                    setState(() {
                                      _bluetoothSearchQuery = '';
                                    });
                                    _bluetoothSearchController.clear();
                                    _notifyBluetoothDialog();
                                  },
                                  icon: const Icon(Icons.clear, size: 18),
                                ),
                        ),
                      ),
                      const SizedBox(height: 8),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          FilterChip(
                            label: const Text('Likely Zebra'),
                            selected: _bluetoothLikelyZebraOnly,
                            onSelected: (value) {
                              setState(() {
                                _bluetoothLikelyZebraOnly = value;
                              });
                              _notifyBluetoothDialog();
                            },
                          ),
                          FilterChip(
                            label: const Text('Only Unpaired'),
                            selected: _bluetoothOnlyUnpaired,
                            onSelected: (value) {
                              setState(() {
                                _bluetoothOnlyUnpaired = value;
                              });
                              _notifyBluetoothDialog();
                            },
                          ),
                          TextButton(
                            onPressed: () {
                              setState(_resetBluetoothFilters);
                              _notifyBluetoothDialog();
                            },
                            child: const Text('Reset Filters'),
                          ),
                        ],
                      ),
                      if (_isBluetoothScanRunning) ...[
                        const SizedBox(height: 12),
                        LinearProgressIndicator(value: _bluetoothScanProgress),
                        const SizedBox(height: 8),
                        Text(
                          'Auto scan ends in ${_bluetoothScanSecondsRemaining}s',
                        ),
                        Text(
                          _canStopBluetoothScan
                              ? 'You can stop the scan now.'
                              : 'Stop Scan unlocks in ${(_bluetoothScanSecondsRemaining - (_bluetoothScanDurationSeconds - _bluetoothStopUnlockSeconds)).clamp(0, _bluetoothStopUnlockSeconds)}s',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                      const SizedBox(height: 12),
                      SizedBox(
                        height: 200,
                        child: devices.isEmpty
                            ? Center(
                                child: Text(
                                  totalDevices == 0
                                      ? 'No Bluetooth devices loaded yet.'
                                      : 'No devices match the current filters.',
                                ),
                              )
                            : ListView.separated(
                                shrinkWrap: true,
                                itemCount: devices.length,
                                itemBuilder: (context, index) {
                                  final device = devices[index];
                                  final isPairing =
                                      _pairingDeviceAddress == device.address;
                                  return ListTile(
                                    dense: true,
                                    contentPadding: EdgeInsets.zero,
                                    title:
                                        Text(device.name ?? 'Unnamed device'),
                                    subtitle: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Text(device.address),
                                        const SizedBox(height: 4),
                                        Wrap(
                                          spacing: 6,
                                          runSpacing: 6,
                                          children: [
                                            if (device.isPaired)
                                              const _DeviceBadge(
                                                  label: 'Paired'),
                                            if (_isLikelyZebraDevice(device))
                                              const _DeviceBadge(
                                                  label: 'Zebra'),
                                          ],
                                        ),
                                      ],
                                    ),
                                    trailing: isPairing
                                        ? const SizedBox(
                                            width: 24,
                                            height: 24,
                                            child: CircularProgressIndicator(
                                              strokeWidth: 2,
                                            ),
                                          )
                                        : ElevatedButton(
                                            onPressed: device.isPaired
                                                ? null
                                                : () async {
                                                    setState(() {
                                                      _pairingDeviceAddress =
                                                          device.address;
                                                    });
                                                    _notifyBluetoothDialog();
                                                    try {
                                                      await _flutterZebraRfidApi
                                                          .pairBluetoothDevice(
                                                        address: device.address,
                                                      );
                                                    } catch (error) {
                                                      if (!mounted) return;
                                                      setState(() {
                                                        _pairingDeviceAddress =
                                                            null;
                                                      });
                                                      _notifyBluetoothDialog();
                                                      _showMessage(
                                                        'Bluetooth pairing failed: $error',
                                                      );
                                                    }
                                                  },
                                            child: Text(
                                              device.isPaired
                                                  ? 'Paired'
                                                  : 'Pair',
                                            ),
                                          ),
                                  );
                                },
                                separatorBuilder: (context, index) =>
                                    const Divider(height: 1),
                              ),
                      ),
                    ],
                  ),
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Close'),
                ),
              ],
            );
          },
        );
      },
    ).whenComplete(() {
      _bluetoothDialogSetState = null;
      _pairingDeviceAddress = null;
      _cancelBluetoothScanTimer();
      _flutterZebraRfidApi.stopBluetoothScan();
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
                      Text('Percentage: ${_batteryData!.percentage}%'),
                      // Raw battery debug line (shows unformatted underlying fields)
                      Text(
                          'Raw: {level: ${_batteryData!.level}, charging: ${_batteryData!.isCharging}, cause: ${_batteryData!.cause}}'),
                      Text('Charging: ${_batteryData!.isCharging}'),
                      Text('Source: ${_batteryData!.sourceLabel}'),
                      if (_batteryData!.healthPercentage != null)
                        Text(
                            'Battery health: ${_batteryData!.healthPercentage}%'),
                      if (_batteryData!.cycleCount != null)
                        Text('Charge cycles: ${_batteryData!.cycleCount}'),
                      Text(
                          'Estimated: ${_batteryData!.isPercentageEstimated ?? false}'),
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
                  onPressed: _isRegionConfigurationError(_lastError)
                      ? () {
                          Navigator.of(context).pop();
                          _diagnosticsDialogOpen = false;
                          _openReaderRegionDialog();
                        }
                      : null,
                  child: const Text('Set Region'),
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
    super.build(context);
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        _isLoading
            ? const ExampleSectionCard(
                child: Center(
                  child: Padding(
                    padding: EdgeInsets.symmetric(vertical: 20),
                    child: CircularProgressIndicator(),
                  ),
                ),
              )
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
        if (_readTags.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: ExampleSectionCard(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 220),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        Text(
                          'Read tags',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const Spacer(),
                        ExampleStatusPill(
                          label: '${_readTags.length}',
                          icon: Icons.sell_outlined,
                        ),
                        const SizedBox(width: 4),
                        IconButton(
                          tooltip: 'Clear scanned tags',
                          onPressed: () => setState(_readTags.clear),
                          icon: const Icon(Icons.clear_all),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Expanded(
                      child: ListView.separated(
                        itemCount: _readTags.length,
                        itemBuilder: (context, index) {
                          final item = _readTags[index];
                          return ListTile(
                            dense: true,
                            contentPadding: EdgeInsets.zero,
                            leading: const Icon(Icons.nfc_outlined),
                            title: Text(
                              item.id,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                            trailing: ExampleStatusPill(
                              label: 'RSSI ${item.rssi}',
                              icon: Icons.network_check,
                            ),
                          );
                        },
                        separatorBuilder: (context, index) =>
                            const Divider(height: 1),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        const SizedBox(height: 12),
        ExampleSectionCard(
          padding: const EdgeInsets.all(12),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 180,
                child: DropdownButtonFormField<ReaderConnectionType>(
                  initialValue: _connectionType,
                  decoration: const InputDecoration(
                    labelText: 'Connection',
                    isDense: true,
                  ),
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
              ElevatedButton.icon(
                onPressed: _openBluetoothPairingDialog,
                icon: const Icon(Icons.bluetooth_searching),
                label: const Text('Pair Reader'),
              ),
              ElevatedButton.icon(
                onPressed: () async {
                  setState(() => _isLoading = true);
                  await _flutterZebraRfidApi.updateAvailableReaders(
                    connectionType: _connectionType,
                  );
                  setState(() => _isLoading = false);
                },
                icon: const Icon(Icons.refresh),
                label: const Text('Get Reader List'),
              ),
              OutlinedButton.icon(
                onPressed: () async {
                  final d = await _flutterZebraRfidApi.diagnostics();
                  setState(() => _diagnostics = d);
                  _showDiagnosticsDialog();
                },
                icon: const Icon(Icons.monitor_heart_outlined),
                label: const Text('Show Diagnostics'),
              ),
              OutlinedButton.icon(
                onPressed: _canConfigureRegion ? _openReaderRegionDialog : null,
                icon: const Icon(Icons.public),
                label: const Text('Set Region'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

List<RfidTag> _mergeReadTags(List<RfidTag> existing, List<RfidTag> incoming) {
  final incomingById = <String, RfidTag>{};
  for (final tag in incoming) {
    incomingById[tag.id] = tag;
  }
  final previous = existing.where((tag) => !incomingById.containsKey(tag.id));
  return [...incomingById.values, ...previous]
      .take(_RfidPageState._maxReadTags)
      .toList(growable: false);
}

class _DeviceBadge extends StatelessWidget {
  const _DeviceBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant,
              fontWeight: FontWeight.w700,
            ),
      ),
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
    final scheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    IconData connectionStatusIcon() {
      switch (connectionStatus) {
        case ConnectionStatus.connecting:
        case ConnectionStatus.disconnecting:
          return Icons.sync;
        case ConnectionStatus.connected:
          return Icons.link;
        case ConnectionStatus.disconnected:
          return Icons.link_off;
        case ConnectionStatus.error:
          return Icons.error_outline;
      }
    }

    IconData batteryStatusIcon() {
      if (batteryData == null) return Icons.battery_unknown;
      if (batteryData!.isCharging) {
        return Icons.battery_charging_full;
      }
      final level = batteryData!.percentage;
      if (level == 0) {
        return Icons.battery_0_bar;
      }
      if (level < 15) {
        return Icons.battery_1_bar;
      }
      if (level < 30) {
        return Icons.battery_2_bar;
      }
      if (level < 45) {
        return Icons.battery_3_bar;
      }
      if (level < 60) {
        return Icons.battery_4_bar;
      }
      if (level < 75) {
        return Icons.battery_5_bar;
      }
      if (level < 90) {
        return Icons.battery_6_bar;
      }
      return Icons.battery_full;
    }

    if (availableReaders.isEmpty) {
      return const ExampleSectionCard(
        padding: EdgeInsets.all(8),
        child: ExampleEmptyState(
          icon: Icons.nfc_outlined,
          title: 'No RFID readers detected',
          message:
              'Pair or connect a Zebra reader, then refresh the reader list.',
          compact: true,
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ExampleSectionCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: scheme.primaryContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Icon(
                      Icons.nfc,
                      color: scheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          currentReader?.name ?? 'Detected readers',
                          style: textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          currentReader == null
                              ? 'Select a reader to connect and begin scanning tags.'
                              : 'Reader ID ${currentReader!.id}',
                          style: textTheme.bodyMedium?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  ExampleStatusPill(
                    label: connectionStatus.name,
                    icon: connectionStatusIcon(),
                    color: _readerStatusColor(context, connectionStatus),
                  ),
                  ExampleStatusPill(
                    label:
                        '${availableReaders.length} reader${availableReaders.length == 1 ? '' : 's'}',
                    icon: Icons.devices_other,
                    color: scheme.tertiary,
                  ),
                  ExampleStatusPill(
                    label: batteryData == null
                        ? 'Battery unknown'
                        : '${batteryData!.percentage}% battery',
                    icon: batteryStatusIcon(),
                    color: batteryData?.isCharging == true
                        ? const Color(0xFF1B7F4A)
                        : scheme.primary,
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (lastError != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: ExampleSectionCard(
              padding: const EdgeInsets.all(12),
              child: Text(
                'Last Error: ${lastError!.code.name} - ${lastError!.message}',
                style: TextStyle(color: scheme.error),
              ),
            ),
          ),
        ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 260),
          child: ListView.separated(
            shrinkWrap: true,
            padding: EdgeInsets.zero,
            itemCount: availableReaders.length,
            itemBuilder: (context, index) {
              final item = availableReaders[index];
              final isCurrentItem = item.id == currentReader?.id;
              final isConnected = isCurrentItem &&
                  connectionStatus == ConnectionStatus.connected;
              return ExampleSectionCard(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    isCurrentItem
                        ? Icons.radio_button_checked
                        : Icons.radio_button_off,
                    color: isCurrentItem
                        ? scheme.primary
                        : scheme.onSurfaceVariant,
                  ),
                  title: Text(item.name ?? item.id.toString()),
                  subtitle: Text('Reader ID ${item.id}'),
                  trailing: Wrap(
                    spacing: 8,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (isCurrentItem)
                        Icon(
                          connectionStatusIcon(),
                          color: _readerStatusColor(context, connectionStatus),
                        ),
                      FilledButton.tonal(
                        onPressed:
                            connectionStatus == ConnectionStatus.connecting ||
                                    connectionStatus ==
                                        ConnectionStatus.disconnecting
                                ? null
                                : () {
                                    if (isCurrentItem && isConnected) {
                                      onDisconnect?.call();
                                    } else {
                                      onConnect?.call(item.id);
                                    }
                                  },
                        child: Text(isCurrentItem && isConnected
                            ? 'Disconnect'
                            : 'Connect'),
                      ),
                      if (isCurrentItem && isConnected)
                        IconButton(
                          tooltip: 'Status',
                          onPressed: onStatus,
                          icon: const Icon(Icons.monitor_heart_outlined),
                        ),
                    ],
                  ),
                ),
              );
            },
            separatorBuilder: (context, index) => const SizedBox(height: 8),
          ),
        ),
      ],
    );
  }
}

Color _readerStatusColor(BuildContext context, ConnectionStatus status) {
  final scheme = Theme.of(context).colorScheme;
  switch (status) {
    case ConnectionStatus.connected:
      return const Color(0xFF1B7F4A);
    case ConnectionStatus.connecting:
    case ConnectionStatus.disconnecting:
      return scheme.primary;
    case ConnectionStatus.error:
      return scheme.error;
    case ConnectionStatus.disconnected:
      return scheme.onSurfaceVariant;
  }
}
