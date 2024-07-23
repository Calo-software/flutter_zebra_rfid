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
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();

    _flutterZebraRfidApi.onAvailableReadersChanged.listen((readers) {
      setState(() => _availableReaders = readers);
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
                      : _ReadersContainer(availableReaders: _availableReaders),
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
  });

  final List<String> availableReaders;

  @override
  Widget build(BuildContext context) {
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
              return Container(
                padding: const EdgeInsets.all(8),
                child: Text(item),
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
