import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_barcode.dart';
import 'package:flutter_zebra_rfid/shared_types.dart';

class BarcodePage extends StatefulWidget {
  const BarcodePage({super.key});

  @override
  State<BarcodePage> createState() => _BarcodePageState();
}

class _BarcodePageState extends State<BarcodePage> {
  final _flutterZebraBarcodeApi = FlutterZebraBarcodeApi();

  List<BarcodeScanner> _availableScanners = [];
  BarcodeScanner? _currentScanner;
  ConnectionStatus _connectionStatus = ConnectionStatus.disconnected;

  bool _isLoading = false;

  @override
  void initState() {
    super.initState();

    _flutterZebraBarcodeApi.onAvailableScannersChanged.listen((scanners) async {
      final scanner = await _flutterZebraBarcodeApi.currentScanner;
      setState(() {
        _availableScanners = scanners;
        _currentScanner = scanner;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Expanded(
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _ScannersContainer(
                  availableScanners: _availableScanners,
                  connectionStatus: _connectionStatus,
                  currentScanner: _currentScanner,
                  onConnect: (id) =>
                      _flutterZebraBarcodeApi.connectScanner(scannerId: id),
                  // onDisconnect: () =>
                  //     _flutterZebraBarcodeApi.disconectCurrentScanner()
                  // onStatus: () => _flutterZebraRfidApi.triggerDeviceStatus(),
                ),
        ),
        // if (_readTags.isNotEmpty)
        //   Padding(
        //     padding: const EdgeInsets.symmetric(vertical: 16),
        //     child: Column(
        //       children: [
        //         const Padding(
        //           padding: EdgeInsets.only(bottom: 16),
        //           child: Text('Read tags:'),
        //         ),
        //         ListView.separated(
        //           shrinkWrap: true,
        //           itemCount: _readTags.length,
        //           itemBuilder: (context, index) {
        //             final item = _readTags[index];
        //             return Container(
        //               color: Colors.white,
        //               child: Row(
        //                 children: [
        //                   Expanded(
        //                     child: Container(
        //                       padding: const EdgeInsets.all(8),
        //                       child: Text(item.id),
        //                     ),
        //                   ),
        //                   Padding(
        //                       padding:
        //                           const EdgeInsets.symmetric(horizontal: 8),
        //                       child: Text(item.rssi.toString()))
        //                 ],
        //               ),
        //             );
        //           },
        //           separatorBuilder: (context, index) =>
        //               Container(height: 1, color: Colors.grey),
        //         ),
        //       ],
        //     ),
        //   ),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            ElevatedButton(
              onPressed: () async {
                setState(() => _isLoading = true);
                await _flutterZebraBarcodeApi.updateAvailableScanners();
                setState(() => _isLoading = false);
              },
              child: const Text('Get Scanner List'),
            ),
          ],
        ),
      ],
    );
  }
}

class _ScannersContainer extends StatelessWidget {
  const _ScannersContainer({
    required this.availableScanners,
    required this.connectionStatus,
    // this.batteryData,
    this.currentScanner,
    this.onConnect,
    this.onDisconnect,
    this.onStatus,
  });

  final List<BarcodeScanner> availableScanners;
  final ConnectionStatus connectionStatus;
  final BarcodeScanner? currentScanner;
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

    if (availableScanners.isEmpty) {
      return const Center(
        child: Text('No barcode scanners detected!'),
      );
    }
    return Column(
      children: [
        const Padding(
          padding: EdgeInsets.only(bottom: 16),
          child: Text('Detected scanners'),
        ),
        Container(
          decoration: BoxDecoration(border: Border.all(color: Colors.black)),
          child: ListView.separated(
            shrinkWrap: true,
            itemCount: availableScanners.length,
            itemBuilder: (context, index) {
              final item = availableScanners[index];
              final isCurrentItem = item.id == currentScanner?.id;
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
