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
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF006E5F),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF6F8F7),
        appBarTheme: const AppBarTheme(centerTitle: false),
        cardTheme: CardThemeData(
          elevation: 0,
          color: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(8),
            side: const BorderSide(color: Color(0xFFE1E7E4)),
          ),
        ),
      ),
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Zebra RFID + Barcode example'),
        ),
        body: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: SafeArea(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 960),
                child: PageView.builder(
                  controller: _pageController,
                  itemCount: 2,
                  onPageChanged: (page) => setState(() => _currentPage = page),
                  itemBuilder: (context, index) =>
                      index == 0 ? const RfidPage() : const BarcodePage(),
                ),
              ),
            ),
          ),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _currentPage,
          onDestinationSelected: _toPage,
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.nfc_outlined),
              selectedIcon: Icon(Icons.nfc),
              label: 'RFID',
            ),
            NavigationDestination(
              icon: Icon(Icons.barcode_reader),
              selectedIcon: Icon(Icons.barcode_reader),
              label: 'Barcode',
            ),
          ],
        ),
      ),
    );
  }

  void _toPage(int page) {
    _pageController.animateToPage(
      page,
      duration: Durations.short2,
      curve: Curves.easeOutCubic,
    );
    setState(() => _currentPage = page);
  }
}
