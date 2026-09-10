import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';

/// Observes the managed Capture Device path without driving automatic retries.
class RecoveryPanel extends StatefulWidget {
  const RecoveryPanel({super.key});

  @override
  State<RecoveryPanel> createState() => _RecoveryPanelState();
}

class _RecoveryPanelState extends State<RecoveryPanel> {
  final _api = FlutterZebraDataCaptureApi();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  final _events = <String>[];
  int _cycle = 0;
  int _tags = 0;
  int _barcodes = 0;
  bool _busy = false;
  String _snapshot = 'No snapshot yet.';

  void _record(String message) {
    final line = '${DateTime.now().toUtc().toIso8601String()} $message';
    _events.add(line);
    if (_events.length > 200) _events.removeAt(0);
    debugPrint('RFID_RECOVERY $line');
  }

  @override
  void initState() {
    super.initState();
    _record('Recovery panel opened');
    _subscriptions
      ..add(
        _api.capture.onCaptureDeviceStatusChanged.listen((device) {
          _record(
            'status=${device.status.name} rfid=${device.rfid?.status.name} '
            'barcode=${device.barcode?.status.name} error=${device.lastError}',
          );
        }),
      )
      ..add(
        _api.rfid.onTagsRead.listen((tags) {
          if (!mounted || tags.isEmpty) return;
          if (_tags == 0) _record('cycle=$_cycle first RFID read');
          setState(() => _tags += tags.length);
        }),
      )
      ..add(
        _api.barcode.onBarcodeRead.listen((barcode) {
          if (!mounted) return;
          _record('cycle=$_cycle barcode read');
          setState(() => _barcodes++);
        }),
      );
  }

  @override
  void dispose() {
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    super.dispose();
  }

  Future<void> _snapshotDiagnostics() async {
    setState(() => _busy = true);
    _record('cycle=$_cycle diagnostics requested');
    final report = <String, Object?>{
      'build': const String.fromEnvironment(
        'RECOVERY_BUILD',
        defaultValue: 'local recovery candidate',
      ),
      'at': DateTime.now().toUtc().toIso8601String(),
      'cycle': _cycle,
      'tagReportsSinceMarker': _tags,
      'barcodesSinceMarker': _barcodes,
      'events': List<String>.of(_events),
    };
    try {
      final d = await _api.rfid.diagnostics().timeout(
        const Duration(seconds: 5),
      );
      report['rfid'] = {
        'state': d.connectionState.name,
        'attempts': d.connectAttempts,
        'lastErrorCode': d.lastErrorCode?.name,
        'lastErrorMessage': d.lastErrorMessage,
        'lastConnectStartMs': d.lastConnectStartMs,
        'lastConnectDurationMs': d.lastConnectDurationMs,
        'inventoryActive': d.inventoryActive,
        'scanningEnabled': d.scanningEnabled,
        'readerPowerState': d.readerPowerState,
        'readerPowerStateError': d.readerPowerStateError,
      };
    } catch (error) {
      report['rfidSnapshotError'] = error.toString();
    }
    try {
      final events = await _api.capture.captureDiagnostics.timeout(
        const Duration(seconds: 5),
      );
      report['capture'] = events
          .map(
            (e) => {
              'timestampMs': e.timestampMs,
              'sequence': e.sequence,
              'category': e.category,
              'operation': e.operation,
              'outcome': e.outcome,
              'detailsJson': e.detailsJson,
            },
          )
          .toList();
    } catch (error) {
      report['captureSnapshotError'] = error.toString();
    }
    final snapshot = const JsonEncoder.withIndent('  ').convert(report);
    // Line-by-line output avoids truncating a large diagnostics payload in logcat.
    for (final line in snapshot.split('\n')) {
      debugPrint('RFID_RECOVERY_SNAPSHOT $line');
    }
    if (!mounted) return;
    setState(() {
      _snapshot = snapshot;
      _busy = false;
    });
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Recovery diagnostics'),
        content: SingleChildScrollView(child: SelectableText(_snapshot)),
        actions: [
          TextButton(
            onPressed: () => Clipboard.setData(ClipboardData(text: _snapshot)),
            child: const Text('Copy'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Close'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Card(
    child: ExpansionTile(
      title: const Text('Recovery test'),
      subtitle: Text(
        'Cycle $_cycle · $_tags RFID reports · $_barcodes barcodes',
      ),
      childrenPadding: const EdgeInsets.all(12),
      children: [
        const Text(
          'Stay on Capture. Connect the sled and verify both scans. '
          'Mark a cycle, then detach/reattach OR remove/replace the sled battery. '
          'Scan again after recovery. Test background/resume separately. '
          'After retry exhaustion, restore the sled and press Connect. '
          'A connected status alone is not a pass.',
        ),
        Wrap(
          spacing: 8,
          children: [
            TextButton(
              onPressed: () => setState(() {
                _cycle++;
                _tags = 0;
                _barcodes = 0;
                _record('cycle=$_cycle marker; scan counters reset');
              }),
              child: const Text('Mark cycle / reset counts'),
            ),
            TextButton(
              onPressed: _busy ? null : _snapshotDiagnostics,
              child: Text(_busy ? 'Collecting…' : 'Snapshot diagnostics'),
            ),
          ],
        ),
      ],
    ),
  );
}
