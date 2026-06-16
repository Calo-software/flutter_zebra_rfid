import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_barcode.dart';
import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:flutter_zebra_rfid_example/example_ui.dart';

class BarcodePage extends StatefulWidget {
  const BarcodePage({super.key});

  @override
  State<BarcodePage> createState() => _BarcodePageState();
}

class _BarcodePageState extends State<BarcodePage>
    with AutomaticKeepAliveClientMixin<BarcodePage> {
  @override
  bool get wantKeepAlive => true;
  final _barcodeApi = FlutterZebraBarcodeApi();
  final List<_BarcodeScan> _scans = [];
  final List<StreamSubscription<dynamic>> _subscriptions = [];

  List<BarcodeScannerEndpoint> _endpoints = [];
  BarcodeScannerEndpoint? _activeEndpoint;
  ConnectionStatus _connectionStatus = ConnectionStatus.disconnected;
  bool _isLoading = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _subscriptions
      ..add(_barcodeApi.onAvailableBarcodeScannersChanged.listen((endpoints) {
        setState(() => _endpoints = endpoints);
      }))
      ..add(_barcodeApi.onActiveBarcodeScannerChanged.listen((endpoint) {
        setState(() => _activeEndpoint = endpoint);
      }))
      ..add(_barcodeApi.onReaderConnectionStatusChanged.listen((status) {
        setState(() => _connectionStatus = status);
      }))
      ..add(_barcodeApi.onBarcodeRead.listen((barcode) {
        setState(() {
          _scans.insert(0, _BarcodeScan(barcode: barcode, at: DateTime.now()));
          if (_scans.length > 50) {
            _scans.removeLast();
          }
        });
      }));
    _refresh();
  }

  @override
  void dispose() {
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final grouped = _groupEndpoints(_endpoints);
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        _StatusHeader(
          activeEndpoint: _activeEndpoint,
          connectionStatus: _connectionStatus,
          message: _message,
          endpointCount: _endpoints.length,
          scanCount: _scans.length,
        ),
        const SizedBox(height: 12),
        ExampleSectionCard(
          padding: const EdgeInsets.all(12),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              ElevatedButton.icon(
                onPressed: _isLoading ? null : _refresh,
                icon: _isLoading
                    ? const SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh),
                label: const Text('Refresh scanners'),
              ),
              OutlinedButton.icon(
                onPressed:
                    _activeEndpoint?.mode == BarcodeScannerMode.scannerSdk
                        ? () => _connectScanner(_activeEndpoint!)
                        : null,
                icon: const Icon(Icons.link),
                label: const Text('Connect external'),
              ),
              OutlinedButton.icon(
                onPressed:
                    _activeEndpoint?.mode == BarcodeScannerMode.scannerSdk
                        ? _disconnectScanner
                        : null,
                icon: const Icon(Icons.link_off),
                label: const Text('Disconnect'),
              ),
              OutlinedButton.icon(
                onPressed: _scans.isEmpty ? null : () => setState(_scans.clear),
                icon: const Icon(Icons.clear_all),
                label: const Text('Clear scans'),
              ),
            ],
          ),
        ),
        if (_endpoints.length > 1 && _activeEndpoint == null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: ExampleSectionCard(
              padding: const EdgeInsets.all(12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.info_outline, color: Colors.amber.shade900),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      'Multiple barcode scanners found. Select the scanner that should handle barcode reads.',
                    ),
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 12),
        if (_endpoints.isEmpty && !_isLoading)
          const ExampleSectionCard(
            padding: EdgeInsets.all(8),
            child: ExampleEmptyState(
              icon: Icons.barcode_reader,
              title: 'No barcode scanners detected',
              message:
                  'Refresh scanners after connecting Zebra hardware or enabling the scanner service.',
              compact: true,
            ),
          ),
        for (final entry in grouped.entries) ...[
          Padding(
            padding: const EdgeInsets.only(top: 12, bottom: 6),
            child: Text(
              entry.key.label,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          for (final endpoint in entry.value)
            _EndpointTile(
              endpoint: endpoint,
              onSelect: () => _selectEndpoint(endpoint),
            ),
        ],
        const SizedBox(height: 16),
        _RecentScansSection(scans: _scans),
      ],
    );
  }

  Map<BarcodeScannerSource, List<BarcodeScannerEndpoint>> _groupEndpoints(
    List<BarcodeScannerEndpoint> endpoints,
  ) {
    final grouped = <BarcodeScannerSource, List<BarcodeScannerEndpoint>>{};
    for (final endpoint in endpoints) {
      grouped.putIfAbsent(endpoint.source, () => []).add(endpoint);
    }
    return grouped;
  }

  Future<void> _refresh() async {
    setState(() {
      _isLoading = true;
      _message = null;
    });
    try {
      await _barcodeApi.refreshBarcodeScanners();
      final active = await _barcodeApi.activeBarcodeScanner;
      if (mounted) {
        setState(() => _activeEndpoint = active);
      }
    } catch (error) {
      if (mounted) {
        setState(() => _message = error.toString());
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _selectEndpoint(BarcodeScannerEndpoint endpoint) async {
    try {
      await _barcodeApi.setActiveBarcodeScanner(
          endpointId: endpoint.endpointId);
      if (endpoint.mode == BarcodeScannerMode.scannerSdk &&
          endpoint.scannerId != null) {
        await _barcodeApi.connectScanner(scannerId: endpoint.scannerId!);
      }
      setState(() => _message = null);
    } catch (error) {
      setState(() => _message = error.toString());
    }
  }

  Future<void> _connectScanner(BarcodeScannerEndpoint endpoint) async {
    final scannerId = endpoint.scannerId;
    if (scannerId == null) return;
    try {
      await _barcodeApi.connectScanner(scannerId: scannerId);
      setState(() => _message = null);
    } catch (error) {
      setState(() => _message = error.toString());
    }
  }

  Future<void> _disconnectScanner() async {
    try {
      await _barcodeApi.disconnectScanner();
      setState(() => _message = null);
    } catch (error) {
      setState(() => _message = error.toString());
    }
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({
    required this.activeEndpoint,
    required this.connectionStatus,
    required this.endpointCount,
    required this.scanCount,
    this.message,
  });

  final BarcodeScannerEndpoint? activeEndpoint;
  final ConnectionStatus connectionStatus;
  final int endpointCount;
  final int scanCount;
  final String? message;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return ExampleSectionCard(
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
                  color: scheme.secondaryContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.document_scanner_outlined,
                  color: scheme.onSecondaryContainer,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      activeEndpoint == null
                          ? 'No active barcode scanner'
                          : activeEndpoint!.displayName,
                      style: textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      activeEndpoint == null
                          ? 'Refresh and select a scanner when multiple hardware paths are present.'
                          : '${activeEndpoint!.source.label} via ${activeEndpoint!.mode.label}',
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
                label: activeEndpoint?.connectionStatus.name ??
                    connectionStatus.name,
                icon: activeEndpoint == null
                    ? Icons.radio_button_unchecked
                    : Icons.radio_button_checked,
                color: _connectionStatusColor(context, connectionStatus),
              ),
              ExampleStatusPill(
                label:
                    '$endpointCount endpoint${endpointCount == 1 ? '' : 's'}',
                icon: Icons.devices_other,
                color: scheme.tertiary,
              ),
              ExampleStatusPill(
                label: '$scanCount scan${scanCount == 1 ? '' : 's'}',
                icon: Icons.qr_code_scanner,
                color: scheme.primary,
              ),
            ],
          ),
          if (activeEndpoint?.mode == BarcodeScannerMode.scannerSdk)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(
                'External connection: ${connectionStatus.name}',
                style: textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
          if (message != null) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: scheme.errorContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                message!,
                style: TextStyle(color: scheme.onErrorContainer),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _RecentScansSection extends StatelessWidget {
  const _RecentScansSection({required this.scans});

  final List<_BarcodeScan> scans;

  @override
  Widget build(BuildContext context) {
    return ExampleSectionCard(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 260),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              children: [
                Text(
                  'Recent scans',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const Spacer(),
                ExampleStatusPill(
                  label: '${scans.length}',
                  icon: Icons.qr_code_scanner,
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (scans.isEmpty)
              const ExampleEmptyState(
                icon: Icons.document_scanner_outlined,
                title: 'No scans yet',
                message: 'Scan a barcode to see the most recent reads here.',
                compact: true,
              )
            else
              Expanded(
                child: ListView.separated(
                  itemCount: scans.length,
                  itemBuilder: (context, index) =>
                      _ScanTile(scan: scans[index]),
                  separatorBuilder: (context, index) =>
                      const SizedBox(height: 8),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

Color _connectionStatusColor(BuildContext context, ConnectionStatus status) {
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

class _EndpointTile extends StatelessWidget {
  const _EndpointTile({
    required this.endpoint,
    required this.onSelect,
  });

  final BarcodeScannerEndpoint endpoint;
  final VoidCallback onSelect;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: ExampleSectionCard(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(
            endpoint.active
                ? Icons.radio_button_checked
                : Icons.radio_button_off,
            color: endpoint.active ? scheme.primary : scheme.onSurfaceVariant,
          ),
          title: Text(endpoint.displayName),
          subtitle: Text(
            '${endpoint.mode.label} - ${endpoint.connectionStatus.name}'
            '${endpoint.zebraScannerIdentifier == null ? '' : ' - ${endpoint.zebraScannerIdentifier}'}',
          ),
          trailing: endpoint.active
              ? const ExampleStatusPill(
                  label: 'Active',
                  icon: Icons.check,
                  color: Color(0xFF1B7F4A),
                )
              : TextButton(onPressed: onSelect, child: const Text('Use')),
          onTap: onSelect,
        ),
      ),
    );
  }
}

class _ScanTile extends StatelessWidget {
  const _ScanTile({required this.scan});

  final _BarcodeScan scan;

  @override
  Widget build(BuildContext context) {
    final barcode = scan.barcode;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: ExampleSectionCard(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.qr_code_2),
          title: SelectableText(barcode.data),
          subtitle: Text(
            '${scan.at.toIso8601String()} - '
            '${barcode.source?.label ?? 'Unknown source'}'
            '${barcode.scannerName == null ? '' : ' - ${barcode.scannerName}'}'
            '${barcode.barcodeType == null ? '' : ' - type ${barcode.barcodeType}'}',
          ),
        ),
      ),
    );
  }
}

class _BarcodeScan {
  _BarcodeScan({required this.barcode, required this.at});

  final Barcode barcode;
  final DateTime at;
}
