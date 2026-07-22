import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_zebra_rfid/flutter_zebra_rfid.dart';
import 'package:flutter_zebra_rfid/shared_types.dart';
import 'package:flutter_zebra_rfid_example/example_ui.dart';

class CaptureDashboard extends StatefulWidget {
  const CaptureDashboard({super.key});

  @override
  State<CaptureDashboard> createState() => _CaptureDashboardState();
}

class _CaptureDashboardState extends State<CaptureDashboard>
    with AutomaticKeepAliveClientMixin<CaptureDashboard> {
  @override
  bool get wantKeepAlive => true;

  final _api = FlutterZebraDataCaptureApi();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  var _devices = <CaptureDevice>[];
  var _barcodeEndpoints = <BarcodeScannerEndpoint>[];
  CaptureDevice? _activeDevice;
  RfidTag? _lastTag;
  Barcode? _lastBarcode;
  BatteryData? _sledBattery;
  DateTime? _sledBatteryUpdatedAt;
  bool _isLoading = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _subscriptions
      ..add(_api.capture.onAvailableCaptureDevicesChanged.listen((devices) {
        setState(() => _devices = devices);
      }))
      ..add(_api.capture.onActiveCaptureDeviceChanged.listen((device) {
        setState(() {
          if (_activeDevice?.id != device?.id) {
            _clearSledBattery();
          }
          _activeDevice = device;
        });
      }))
      ..add(_api.capture.onCaptureDeviceStatusChanged.listen((device) {
        setState(() => _activeDevice = device);
      }))
      ..add(_api.barcode.onAvailableBarcodeScannersChanged.listen((endpoints) {
        setState(() => _barcodeEndpoints = endpoints);
      }))
      ..add(_api.rfid.onTagsRead.listen((tags) {
        if (tags.isNotEmpty) setState(() => _lastTag = tags.first);
      }))
      ..add(_api.rfid.onBatteryDataReceived.listen((battery) {
        if (!mounted) return;
        setState(() {
          _sledBattery = battery;
          _sledBatteryUpdatedAt = DateTime.now();
        });
      }))
      ..add(_api.rfid.onReaderConnectionStatusChanged.listen((status) {
        if (!mounted || status == ConnectionStatus.connected) return;
        if (status == ConnectionStatus.disconnected ||
            status == ConnectionStatus.error) {
          setState(_clearSledBattery);
        }
      }))
      ..add(_api.barcode.onBarcodeRead.listen((barcode) {
        setState(() => _lastBarcode = barcode);
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
    return CaptureDashboardView(
      devices: _devices,
      activeDevice: _activeDevice,
      barcodeEndpoints: _barcodeEndpoints,
      lastTag: _lastTag,
      lastBarcode: _lastBarcode,
      sledBattery: _sledBattery,
      sledBatteryUpdatedAt: _sledBatteryUpdatedAt,
      isLoading: _isLoading,
      message: _message,
      onRefresh: _refresh,
      onConnect: _connect,
      onDisconnect: _disconnect,
      onOverrideBarcode: _overrideBarcode,
    );
  }

  void _clearSledBattery() {
    _sledBattery = null;
    _sledBatteryUpdatedAt = null;
  }

  Future<void> _refresh() async {
    setState(() {
      _isLoading = true;
      _message = null;
    });
    try {
      await _api.capture.refreshCaptureDevices();
      final active = await _api.capture.activeCaptureDevice;
      if (mounted) setState(() => _activeDevice = active);
    } catch (error) {
      if (mounted) setState(() => _message = error.toString());
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _connect(CaptureDevice device) async {
    setState(() => _message = null);
    try {
      await _api.capture.connectCaptureDevice(
        captureDeviceId: device.id,
        rfidConfig: ReaderConfig(
          transmitPowerIndex: 299,
          beeperVolume: ReaderBeeperVolume.medium,
          enableDynamicPower: false,
          enableLedBlink: true,
          batchMode: ReaderConfigBatchMode.auto,
          scanBatchMode: ReaderConfigBatchMode.auto,
        ),
      );
    } catch (error) {
      if (mounted) setState(() => _message = error.toString());
    }
  }

  Future<void> _disconnect(CaptureDevice device) async {
    setState(() => _message = null);
    try {
      await _api.capture.disconnectCaptureDevice(captureDeviceId: device.id);
    } catch (error) {
      if (mounted) setState(() => _message = error.toString());
    }
  }

  Future<void> _overrideBarcode(
    CaptureDevice device,
    BarcodeScannerEndpoint endpoint,
  ) async {
    setState(() => _message = null);
    try {
      await _api.capture.setCaptureDeviceBarcodeOverride(
        captureDeviceId: device.id,
        barcodeEndpointId: endpoint.endpointId,
      );
    } catch (error) {
      if (mounted) setState(() => _message = error.toString());
    }
  }
}

class CaptureDashboardView extends StatelessWidget {
  const CaptureDashboardView({
    super.key,
    required this.devices,
    required this.activeDevice,
    required this.barcodeEndpoints,
    required this.lastTag,
    required this.lastBarcode,
    required this.sledBattery,
    required this.sledBatteryUpdatedAt,
    required this.isLoading,
    required this.onRefresh,
    required this.onConnect,
    required this.onDisconnect,
    required this.onOverrideBarcode,
    this.message,
  });

  final List<CaptureDevice> devices;
  final CaptureDevice? activeDevice;
  final List<BarcodeScannerEndpoint> barcodeEndpoints;
  final RfidTag? lastTag;
  final Barcode? lastBarcode;
  final BatteryData? sledBattery;
  final DateTime? sledBatteryUpdatedAt;
  final bool isLoading;
  final String? message;
  final Future<void> Function() onRefresh;
  final Future<void> Function(CaptureDevice device) onConnect;
  final Future<void> Function(CaptureDevice device) onDisconnect;
  final Future<void> Function(
    CaptureDevice device,
    BarcodeScannerEndpoint endpoint,
  ) onOverrideBarcode;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
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
                      Icons.settings_input_component_outlined,
                      color: scheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          activeDevice?.displayName ??
                              'No active Capture Device',
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          activeDevice == null
                              ? 'Refresh and connect one physical setup for RFID and barcode capture.'
                              : '${activeDevice!.topology.label} - ${activeDevice!.matchConfidence.label}',
                          style: Theme.of(context)
                              .textTheme
                              .bodyMedium
                              ?.copyWith(color: scheme.onSurfaceVariant),
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
                    label: activeDevice?.status.label ?? 'Disconnected',
                    icon: Icons.radio_button_checked,
                    color: _statusColor(context, activeDevice?.status),
                  ),
                  ExampleStatusPill(
                    label:
                        '${devices.length} Capture Device${devices.length == 1 ? '' : 's'}',
                    icon: Icons.devices_other,
                    color: scheme.tertiary,
                  ),
                  if (sledBattery != null || activeDevice?.rfid != null)
                    _SledBatteryPill(
                      battery: sledBattery,
                      updatedAt: sledBatteryUpdatedAt,
                    ),
                  ExampleStatusPill(
                    label:
                        lastTag == null ? 'No RFID tag' : 'RFID ${lastTag!.id}',
                    icon: Icons.nfc,
                    color: scheme.primary,
                  ),
                  ExampleStatusPill(
                    label: lastBarcode == null
                        ? 'No barcode'
                        : 'Barcode ${lastBarcode!.data}',
                    icon: Icons.qr_code_scanner,
                    color: scheme.secondary,
                  ),
                ],
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
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            ElevatedButton.icon(
              onPressed: isLoading ? null : onRefresh,
              icon: isLoading
                  ? const SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.refresh),
              label: const Text('Refresh Capture Devices'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Expanded(
          child: devices.isEmpty && !isLoading
              ? const ExampleSectionCard(
                  child: ExampleEmptyState(
                    icon: Icons.settings_input_component_outlined,
                    title: 'No Capture Devices detected',
                    message:
                        'Pair or dock Zebra hardware, then refresh Capture Devices.',
                  ),
                )
              : ListView.separated(
                  padding: EdgeInsets.zero,
                  itemBuilder: (context, index) => _CaptureDeviceTile(
                    device: devices[index],
                    barcodeEndpoints: barcodeEndpoints,
                    onConnect: onConnect,
                    onDisconnect: onDisconnect,
                    onOverrideBarcode: onOverrideBarcode,
                  ),
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemCount: devices.length,
                ),
        ),
      ],
    );
  }
}

class _SledBatteryPill extends StatelessWidget {
  const _SledBatteryPill({required this.battery, required this.updatedAt});

  final BatteryData? battery;
  final DateTime? updatedAt;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final percentage = battery?.percentage;
    final details = <String>[
      if (battery != null) 'Source: ${battery!.sourceLabel}',
      if (battery != null) 'Charging: ${battery!.isCharging ? 'yes' : 'no'}',
      if (battery?.healthPercentage != null)
        'Health: ${battery!.healthPercentage}%',
      if (battery?.cycleCount != null) 'Charge cycles: ${battery!.cycleCount}',
      if (updatedAt != null) 'Updated: ${updatedAt!.toLocal()}',
    ];

    return Tooltip(
      message: details.isEmpty
          ? 'Waiting for battery data from the connected RFID sled.'
          : details.join('\n'),
      child: ExampleStatusPill(
        label: percentage == null
            ? 'Sled battery unknown'
            : 'Sled battery $percentage%',
        icon: _batteryIcon(battery),
        color: percentage != null && percentage <= 15
            ? scheme.error
            : scheme.primary,
      ),
    );
  }
}

IconData _batteryIcon(BatteryData? battery) {
  if (battery == null) return Icons.battery_unknown;
  if (battery.isCharging) return Icons.battery_charging_full;
  return switch (battery.percentage) {
    0 => Icons.battery_0_bar,
    < 15 => Icons.battery_1_bar,
    < 30 => Icons.battery_2_bar,
    < 45 => Icons.battery_3_bar,
    < 60 => Icons.battery_4_bar,
    < 75 => Icons.battery_5_bar,
    < 90 => Icons.battery_6_bar,
    _ => Icons.battery_full,
  };
}

class _CaptureDeviceTile extends StatelessWidget {
  const _CaptureDeviceTile({
    required this.device,
    required this.barcodeEndpoints,
    required this.onConnect,
    required this.onDisconnect,
    required this.onOverrideBarcode,
  });

  final CaptureDevice device;
  final List<BarcodeScannerEndpoint> barcodeEndpoints;
  final Future<void> Function(CaptureDevice device) onConnect;
  final Future<void> Function(CaptureDevice device) onDisconnect;
  final Future<void> Function(
    CaptureDevice device,
    BarcodeScannerEndpoint endpoint,
  ) onOverrideBarcode;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ExampleSectionCard(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                device.active
                    ? Icons.radio_button_checked
                    : Icons.radio_button_off,
                color: device.active ? scheme.primary : scheme.onSurfaceVariant,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      device.displayName,
                      style: Theme.of(context)
                          .textTheme
                          .titleSmall
                          ?.copyWith(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '${device.topology.label} - ${device.matchReason}',
                      style: Theme.of(context)
                          .textTheme
                          .bodySmall
                          ?.copyWith(color: scheme.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              ExampleStatusPill(
                label: device.status.label,
                icon: Icons.sensors,
                color: _statusColor(context, device.status),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _CapabilityPill(
                icon: Icons.nfc,
                label: device.rfid == null
                    ? 'RFID unavailable'
                    : 'RFID ${device.rfid!.status.label}',
                error: device.rfid?.error,
              ),
              _CapabilityPill(
                icon: Icons.qr_code_scanner,
                label: device.barcode == null
                    ? 'Barcode unavailable'
                    : 'Barcode ${device.barcode!.status.label}',
                error: device.barcode?.error,
              ),
              ExampleStatusPill(
                label: device.matchConfidence.label,
                icon: Icons.hub_outlined,
                color: scheme.tertiary,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ElevatedButton.icon(
                onPressed: device.status == CaptureDeviceStatus.connecting
                    ? null
                    : () => onConnect(device),
                icon: const Icon(Icons.link),
                label: const Text('Connect'),
              ),
              OutlinedButton.icon(
                onPressed: device.active ? () => onDisconnect(device) : null,
                icon: const Icon(Icons.link_off),
                label: const Text('Disconnect'),
              ),
              OutlinedButton.icon(
                onPressed: barcodeEndpoints.isEmpty
                    ? null
                    : () => _showOverrideSheet(context),
                icon: const Icon(Icons.tune),
                label: const Text('Barcode Override'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _showOverrideSheet(BuildContext context) async {
    final endpoint = await showModalBottomSheet<BarcodeScannerEndpoint>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
              child: Text(
                'Select Barcode Endpoint',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            for (final endpoint in barcodeEndpoints)
              ListTile(
                leading: Icon(
                  endpoint.active
                      ? Icons.radio_button_checked
                      : Icons.radio_button_off,
                ),
                title: Text(endpoint.displayName),
                subtitle: Text(
                  '${endpoint.source.label} - ${endpoint.mode.label}',
                ),
                onTap: () => Navigator.of(context).pop(endpoint),
              ),
          ],
        ),
      ),
    );
    if (endpoint != null) {
      await onOverrideBarcode(device, endpoint);
    }
  }
}

class _CapabilityPill extends StatelessWidget {
  const _CapabilityPill({
    required this.icon,
    required this.label,
    this.error,
  });

  final IconData icon;
  final String label;
  final String? error;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: error ?? label,
      child: ExampleStatusPill(
        label: label,
        icon: icon,
        color: error == null ? scheme.primary : scheme.error,
      ),
    );
  }
}

Color _statusColor(BuildContext context, CaptureDeviceStatus? status) {
  final scheme = Theme.of(context).colorScheme;
  return switch (status) {
    CaptureDeviceStatus.connected => const Color(0xFF1B7F4A),
    CaptureDeviceStatus.degraded => const Color(0xFFB26A00),
    CaptureDeviceStatus.connecting ||
    CaptureDeviceStatus.disconnecting =>
      scheme.primary,
    CaptureDeviceStatus.error => scheme.error,
    CaptureDeviceStatus.disconnected || null => scheme.onSurfaceVariant,
  };
}
