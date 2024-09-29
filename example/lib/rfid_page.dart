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
        _connectionStatus = status;
        _currentReader = reader;

        if (status == ConnectionStatus.connected) {
          // configure reader
          _flutterZebraRfidApi.configureReader(
              config: ReaderConfig(
                transmitPowerIndex:
                    299, //_currentReader?.info?.transmitPowerLevels.length-1,
                beeperVolume: ReaderBeeperVolume.medium,
                enableDynamicPower: false,
                enableLedBlink: true,
                batchMode: ReaderConfigBatchMode.auto,
                scanBatchMode: ReaderConfigBatchMode.auto,
              ),
              shouldPersist: false);
        }
      });
    });

    _flutterZebraRfidApi.onTagsRead.listen(
      (tags) => setState(() => _readTags = tags),
    );

    _flutterZebraRfidApi.onBatteryDataReceived.listen(
      (batteryData) => setState(() => _batteryData = batteryData),
    );
  }

  @override
  Widget build(BuildContext context) {
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
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(right: 16),
                child: DropdownButton<ReaderConnectionType>(
                  value: _connectionType,
                  items: const [
                    DropdownMenuItem(
                      value: ReaderConnectionType.usb,
                      child: Text('USB'),
                    ),
                    DropdownMenuItem(
                      value: ReaderConnectionType.bluetooth,
                      child: Text('Bluetooth'),
                    ),
                  ],
                  onChanged: (value) => setState(
                    () => _connectionType = value!,
                  ),
                ),
              ),
            ),
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
          ],
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
    this.onConnect,
    this.onDisconnect,
    this.onStatus,
  });

  final List<Reader> availableReaders;
  final ConnectionStatus connectionStatus;
  final BatteryData? batteryData;
  final Reader? currentReader;
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
        default:
          return Container();
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
        Container(
          decoration: BoxDecoration(border: Border.all(color: Colors.black)),
          child: ListView.separated(
            shrinkWrap: true,
            itemCount: availableReaders.length,
            itemBuilder: (context, index) {
              final item = availableReaders[index];
              final isCurrentItem = item.id == currentReader?.id;
              final isConnected = isCurrentItem &&
                  connectionStatus == ReaderConnectionStatus.connected;
              return Container(
                color: Colors.white,
                child: GestureDetector(
                  onTap: () {
                    if (connectionStatus != ReaderConnectionStatus.connecting &&
                        connectionStatus !=
                            ReaderConnectionStatus.disconnecting) {
                      showDialog(
                        context: context,
                        builder: (context) => Center(
                          child: Wrap(
                            children: [
                              Container(
                                color: Colors.white,
                                padding: const EdgeInsets.all(16),
                                child: Column(
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
                                                // disconnect
                                                onDisconnect?.call();
                                                Navigator.of(context).pop();
                                              } else {
                                                // connect
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
      ],
    );
  }
}
