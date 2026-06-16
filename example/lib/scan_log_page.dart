import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid_example/example_ui.dart';

enum ScanLogEntryType { rfid, barcode }

class ScanLogEntry {
  ScanLogEntry({
    required this.type,
    required this.value,
    required this.at,
    this.detail,
    this.signal,
  });

  factory ScanLogEntry.rfid(RfidTag tag, {DateTime? at}) {
    return ScanLogEntry(
      type: ScanLogEntryType.rfid,
      value: tag.id,
      at: at ?? DateTime.now(),
      detail: 'RFID tag',
      signal: 'RSSI ${tag.rssi}',
    );
  }

  factory ScanLogEntry.barcode(Barcode barcode, {DateTime? at}) {
    final detailParts = [
      barcode.source?.label ?? 'Unknown source',
      if (barcode.scannerName != null) barcode.scannerName!,
      if (barcode.barcodeType != null) 'type ${barcode.barcodeType}',
    ];
    return ScanLogEntry(
      type: ScanLogEntryType.barcode,
      value: barcode.data,
      at: at ?? DateTime.now(),
      detail: detailParts.join(' - '),
    );
  }

  final ScanLogEntryType type;
  final String value;
  final DateTime at;
  final String? detail;
  final String? signal;
}

class ScanLogPage extends StatelessWidget {
  const ScanLogPage({
    super.key,
    required this.entries,
    required this.onClear,
  });

  final List<ScanLogEntry> entries;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return ScanLogView(entries: entries, onClear: onClear);
  }
}

class ScanLogView extends StatelessWidget {
  const ScanLogView({
    super.key,
    required this.entries,
    required this.onClear,
  });

  final List<ScanLogEntry> entries;
  final VoidCallback onClear;

  int get _rfidCount =>
      entries.where((entry) => entry.type == ScanLogEntryType.rfid).length;

  int get _barcodeCount =>
      entries.where((entry) => entry.type == ScanLogEntryType.barcode).length;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListView(
      padding: EdgeInsets.zero,
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
                      Icons.fact_check_outlined,
                      color: scheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Scan Log',
                          style:
                              Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.w700,
                                  ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Combined RFID and barcode reads from the active capture setup.',
                          style:
                              Theme.of(context).textTheme.bodyMedium?.copyWith(
                                    color: scheme.onSurfaceVariant,
                                  ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: 'Clear scan log',
                    onPressed: entries.isEmpty ? null : onClear,
                    icon: const Icon(Icons.clear_all),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  ExampleStatusPill(
                    label: '${entries.length} total',
                    icon: Icons.list_alt,
                  ),
                  ExampleStatusPill(
                    label: '$_rfidCount RFID',
                    icon: Icons.nfc,
                    color: _rfidColor,
                  ),
                  ExampleStatusPill(
                    label: '$_barcodeCount barcode',
                    icon: Icons.qr_code_scanner,
                    color: _barcodeColor,
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        if (entries.isEmpty)
          const ExampleSectionCard(
            padding: EdgeInsets.all(8),
            child: ExampleEmptyState(
              icon: Icons.fact_check_outlined,
              title: 'No scans logged yet',
              message: 'Scan an RFID tag or barcode to see reads here.',
              compact: true,
            ),
          )
        else
          ExampleSectionCard(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
            child: ListView.separated(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: entries.length,
              itemBuilder: (context, index) => _ScanLogTile(
                entry: entries[index],
              ),
              separatorBuilder: (context, index) => const Divider(height: 1),
            ),
          ),
      ],
    );
  }
}

class _ScanLogTile extends StatelessWidget {
  const _ScanLogTile({required this.entry});

  final ScanLogEntry entry;

  @override
  Widget build(BuildContext context) {
    final color =
        entry.type == ScanLogEntryType.rfid ? _rfidColor : _barcodeColor;
    final icon =
        entry.type == ScanLogEntryType.rfid ? Icons.nfc : Icons.qr_code_2;
    final label = entry.type == ScanLogEntryType.rfid ? 'RFID' : 'Barcode';
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Container(
        width: 36,
        height: 36,
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(icon, color: color),
      ),
      title: SelectableText(entry.value),
      subtitle: Text(
        [
          _timeLabel(entry.at),
          if (entry.detail != null) entry.detail!,
          if (entry.signal != null) entry.signal!,
        ].join(' - '),
      ),
      trailing: ExampleStatusPill(label: label, color: color),
    );
  }
}

String _timeLabel(DateTime value) {
  final hour = value.hour.toString().padLeft(2, '0');
  final minute = value.minute.toString().padLeft(2, '0');
  final second = value.second.toString().padLeft(2, '0');
  return '$hour:$minute:$second';
}

const _rfidColor = Color(0xFF1B7F4A);
const _barcodeColor = Color(0xFF0B63CE);
