import 'package:flutter/material.dart';

import 'package:flutter_zebra_rfid_example/barcode.dart';
import 'package:flutter_zebra_rfid_example/rfid_page.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  final _pageController = PageController(initialPage: 0, keepPage: true);

  int _currentPage = 0;

  @override
  void initState() {
    super.initState();
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
            child: PageView.builder(
              controller: _pageController,
              itemBuilder: (context, index) =>
                  index == 0 ? const RfidPage() : const BarcodePage(),
            ),
          ),
        ),
        bottomNavigationBar: BottomNavigationBar(
          currentIndex: _currentPage,
          backgroundColor: Colors.grey.shade300,
          items: [
            BottomNavigationBarItem(
                icon: IconButton(
                  icon: const Icon(Icons.nfc),
                  onPressed: () => _toPage(0),
                ),
                label: 'RFID'),
            BottomNavigationBarItem(
              icon: IconButton(
                icon: const Icon(Icons.barcode_reader),
                onPressed: () => _toPage(1),
              ),
              label: 'Barcode',
            ),
          ],
        ),
      ),
    );
  }

  void _toPage(int page) {
    _pageController.animateToPage(page,
        duration: Durations.short2, curve: Curves.easeIn);
    setState(() => _currentPage = page);
  }
}
