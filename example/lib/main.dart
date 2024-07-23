import 'package:flutter/material.dart';

import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.g.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _flutterZebraRfidApi = FlutterZebraRfidApi();

  List<String> _availableReaders = [];
  ReaderConnectionStatus _connectionStatus =
      ReaderConnectionStatus.disconnected;
  String? _currentReaderName;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();

    _flutterZebraRfidApi.onAvailableReadersChanged.listen((readers) async {
      final readerName = await _flutterZebraRfidApi.currentReaderName;
      setState(() {
        _availableReaders = readers;
        _currentReaderName = readerName;
      });
    });

    _flutterZebraRfidApi.onReaderConnectionStatusChanged.listen((status) async {
      final readerName = await _flutterZebraRfidApi.currentReaderName;
      setState(() {
        _connectionStatus = status;
        _currentReaderName = readerName;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Zebra Reader API3 example app'),
        ),
        body: Padding(
          padding: const EdgeInsets.all(24),
          child: SafeArea(
            child: Column(
              children: [
                Expanded(
                  child: _isLoading
                      ? const Center(child: CircularProgressIndicator())
                      : _ReadersContainer(
                          availableReaders: _availableReaders,
                          connectionStatus: _connectionStatus,
                          currentReaderName: _currentReaderName,
                          onConnect: (name) =>
                              _flutterZebraRfidApi.connectReader(
                            readerName: name,
                          ),
                          onDisconnect: () =>
                              _flutterZebraRfidApi.disconectCurrentReader(),
                        ),
                ),
                ElevatedButton(
                  onPressed: () async {
                    setState(() => _isLoading = true);
                    await _flutterZebraRfidApi.updateAvailableReaders(
                      connectionType: ReaderConnectionType.usb,
                    );
                    setState(() => _isLoading = false);
                  },
                  child: const Text('Get Reader List'),
                )
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ReadersContainer extends StatelessWidget {
  const _ReadersContainer({
    required this.availableReaders,
    required this.connectionStatus,
    this.currentReaderName,
    this.onConnect,
    this.onDisconnect,
  });

  final List<String> availableReaders;
  final ReaderConnectionStatus connectionStatus;
  final String? currentReaderName;
  final Function(String)? onConnect;
  final VoidCallback? onDisconnect;

  @override
  Widget build(BuildContext context) {
    Widget connectionStatusIcon() {
      switch (connectionStatus) {
        case ReaderConnectionStatus.connecting:
        case ReaderConnectionStatus.disconnecting:
          return const SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator());
        case ReaderConnectionStatus.connected:
          return const Icon(Icons.wifi_outlined, color: Colors.green);
        case ReaderConnectionStatus.disconnected:
          return const Icon(Icons.wifi_off_outlined, color: Colors.red);
        default:
          return Container();
      }
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
              final isCurrentItem = item == currentReaderName;
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
                                    Text(item),
                                    Padding(
                                      padding: const EdgeInsets.only(top: 8),
                                      child: ElevatedButton(
                                        onPressed: () {
                                          if (isCurrentItem && isConnected) {
                                            // disconnect
                                            onDisconnect?.call();
                                            Navigator.of(context).pop();
                                          } else {
                                            // connect
                                            onConnect?.call(item);
                                            Navigator.of(context).pop();
                                          }
                                        },
                                        child: Text(isCurrentItem && isConnected
                                            ? 'Disconnect'
                                            : 'Connect'),
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
                          child: Text(item),
                        ),
                      ),
                      if (isCurrentItem)
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          child: connectionStatusIcon(),
                        )
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
