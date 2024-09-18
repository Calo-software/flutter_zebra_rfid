// Autogenerated from Pigeon (v21.2.0), do not edit directly.
// See also: https://pub.dev/packages/pigeon
// ignore_for_file: public_member_api_docs, non_constant_identifier_names, avoid_as, unused_import, unnecessary_parenthesis, prefer_null_aware_operators, omit_local_variable_types, unused_shown_name, unnecessary_import, no_leading_underscores_for_local_identifiers

import 'dart:async';
import 'dart:typed_data' show Float64List, Int32List, Int64List, Uint8List;

import 'package:flutter/foundation.dart' show ReadBuffer, WriteBuffer;
import 'package:flutter/services.dart';

PlatformException _createConnectionError(String channelName) {
  return PlatformException(
    code: 'channel-error',
    message: 'Unable to establish connection on channel: "$channelName".',
  );
}

List<Object?> wrapResponse({Object? result, PlatformException? error, bool empty = false}) {
  if (empty) {
    return <Object?>[];
  }
  if (error == null) {
    return <Object?>[result];
  }
  return <Object?>[error.code, error.message, error.details];
}

enum ReaderConnectionType {
  bluetooth,
  usb,
  all,
}

enum ReaderConnectionStatus {
  connecting,
  connected,
  disconnecting,
  disconnected,
  error,
}

enum ReaderConfigBatchMode {
  auto,
  enabled,
  disabled,
}

enum ReaderBeeperVolume {
  quiet,
  low,
  medium,
  high,
}

class Reader {
  Reader({
    this.name,
    required this.id,
    this.info,
  });

  String? name;

  int id;

  ReaderInfo? info;

  Object encode() {
    return <Object?>[
      name,
      id,
      info,
    ];
  }

  static Reader decode(Object result) {
    result as List<Object?>;
    return Reader(
      name: result[0] as String?,
      id: result[1]! as int,
      info: result[2] as ReaderInfo?,
    );
  }
}

class ReaderConfig {
  ReaderConfig({
    this.transmitPowerIndex,
    this.beeperVolume,
    this.enableDynamicPower,
    this.enableLedBlink,
    this.batchMode,
    this.scanBatchMode,
  });

  int? transmitPowerIndex;

  ReaderBeeperVolume? beeperVolume;

  bool? enableDynamicPower;

  bool? enableLedBlink;

  ReaderConfigBatchMode? batchMode;

  ReaderConfigBatchMode? scanBatchMode;

  Object encode() {
    return <Object?>[
      transmitPowerIndex,
      beeperVolume,
      enableDynamicPower,
      enableLedBlink,
      batchMode,
      scanBatchMode,
    ];
  }

  static ReaderConfig decode(Object result) {
    result as List<Object?>;
    return ReaderConfig(
      transmitPowerIndex: result[0] as int?,
      beeperVolume: result[1] as ReaderBeeperVolume?,
      enableDynamicPower: result[2] as bool?,
      enableLedBlink: result[3] as bool?,
      batchMode: result[4] as ReaderConfigBatchMode?,
      scanBatchMode: result[5] as ReaderConfigBatchMode?,
    );
  }
}

class ReaderInfo {
  ReaderInfo({
    required this.transmitPowerLevels,
    required this.firmwareVersion,
    required this.modelVersion,
    required this.scannerName,
    required this.serialNumber,
  });

  List<Object?> transmitPowerLevels;

  String firmwareVersion;

  String modelVersion;

  String scannerName;

  String serialNumber;

  Object encode() {
    return <Object?>[
      transmitPowerLevels,
      firmwareVersion,
      modelVersion,
      scannerName,
      serialNumber,
    ];
  }

  static ReaderInfo decode(Object result) {
    result as List<Object?>;
    return ReaderInfo(
      transmitPowerLevels: result[0]! as List<Object?>,
      firmwareVersion: result[1]! as String,
      modelVersion: result[2]! as String,
      scannerName: result[3]! as String,
      serialNumber: result[4]! as String,
    );
  }
}

class RfidTag {
  RfidTag({
    required this.id,
    required this.rssi,
  });

  String id;

  int rssi;

  Object encode() {
    return <Object?>[
      id,
      rssi,
    ];
  }

  static RfidTag decode(Object result) {
    result as List<Object?>;
    return RfidTag(
      id: result[0]! as String,
      rssi: result[1]! as int,
    );
  }
}

class BatteryData {
  BatteryData({
    required this.level,
    required this.isCharging,
    required this.cause,
  });

  int level;

  bool isCharging;

  String cause;

  Object encode() {
    return <Object?>[
      level,
      isCharging,
      cause,
    ];
  }

  static BatteryData decode(Object result) {
    result as List<Object?>;
    return BatteryData(
      level: result[0]! as int,
      isCharging: result[1]! as bool,
      cause: result[2]! as String,
    );
  }
}


class _PigeonCodec extends StandardMessageCodec {
  const _PigeonCodec();
  @override
  void writeValue(WriteBuffer buffer, Object? value) {
    if (value is ReaderConnectionType) {
      buffer.putUint8(129);
      writeValue(buffer, value.index);
    } else     if (value is ReaderConnectionStatus) {
      buffer.putUint8(130);
      writeValue(buffer, value.index);
    } else     if (value is ReaderConfigBatchMode) {
      buffer.putUint8(131);
      writeValue(buffer, value.index);
    } else     if (value is ReaderBeeperVolume) {
      buffer.putUint8(132);
      writeValue(buffer, value.index);
    } else     if (value is Reader) {
      buffer.putUint8(133);
      writeValue(buffer, value.encode());
    } else     if (value is ReaderConfig) {
      buffer.putUint8(134);
      writeValue(buffer, value.encode());
    } else     if (value is ReaderInfo) {
      buffer.putUint8(135);
      writeValue(buffer, value.encode());
    } else     if (value is RfidTag) {
      buffer.putUint8(136);
      writeValue(buffer, value.encode());
    } else     if (value is BatteryData) {
      buffer.putUint8(137);
      writeValue(buffer, value.encode());
    } else {
      super.writeValue(buffer, value);
    }
  }

  @override
  Object? readValueOfType(int type, ReadBuffer buffer) {
    switch (type) {
      case 129: 
        final int? value = readValue(buffer) as int?;
        return value == null ? null : ReaderConnectionType.values[value];
      case 130: 
        final int? value = readValue(buffer) as int?;
        return value == null ? null : ReaderConnectionStatus.values[value];
      case 131: 
        final int? value = readValue(buffer) as int?;
        return value == null ? null : ReaderConfigBatchMode.values[value];
      case 132: 
        final int? value = readValue(buffer) as int?;
        return value == null ? null : ReaderBeeperVolume.values[value];
      case 133: 
        return Reader.decode(readValue(buffer)!);
      case 134: 
        return ReaderConfig.decode(readValue(buffer)!);
      case 135: 
        return ReaderInfo.decode(readValue(buffer)!);
      case 136: 
        return RfidTag.decode(readValue(buffer)!);
      case 137: 
        return BatteryData.decode(readValue(buffer)!);
      default:
        return super.readValueOfType(type, buffer);
    }
  }
}

class FlutterZebraRfid {
  /// Constructor for [FlutterZebraRfid].  The [binaryMessenger] named argument is
  /// available for dependency injection.  If it is left null, the default
  /// BinaryMessenger will be used which routes to the host platform.
  FlutterZebraRfid({BinaryMessenger? binaryMessenger, String messageChannelSuffix = ''})
      : pigeonVar_binaryMessenger = binaryMessenger,
        pigeonVar_messageChannelSuffix = messageChannelSuffix.isNotEmpty ? '.$messageChannelSuffix' : '';
  final BinaryMessenger? pigeonVar_binaryMessenger;

  static const MessageCodec<Object?> pigeonChannelCodec = _PigeonCodec();

  final String pigeonVar_messageChannelSuffix;

  /// Returns list with names of available readers for specified `connectionType`.
  Future<void> updateAvailableReaders(ReaderConnectionType connectionType) async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.updateAvailableReaders$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(<Object?>[connectionType]) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return;
    }
  }

  /// Connects to a reader with `readerId` ID.
  Future<void> connectReader(int readerId) async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.connectReader$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(<Object?>[readerId]) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return;
    }
  }

  /// Configures reader with `config`.
  Future<void> configureReader(ReaderConfig config, bool shouldPersist) async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.configureReader$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(<Object?>[config, shouldPersist]) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return;
    }
  }

  /// Disconnects a reader with `readerName` name.
  Future<void> disconnectReader() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.disconnectReader$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(null) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return;
    }
  }

  /// Trigger device status
  Future<void> triggerDeviceStatus() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.triggerDeviceStatus$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(null) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return;
    }
  }

  /// Reader currently in use
  Future<Reader?> currentReader() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.currentReader$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(null) as List<Object?>?;
    if (pigeonVar_replyList == null) {
      throw _createConnectionError(pigeonVar_channelName);
    } else if (pigeonVar_replyList.length > 1) {
      throw PlatformException(
        code: pigeonVar_replyList[0]! as String,
        message: pigeonVar_replyList[1] as String?,
        details: pigeonVar_replyList[2],
      );
    } else {
      return (pigeonVar_replyList[0] as Reader?);
    }
  }
}

abstract class FlutterZebraRfidCallbacks {
  static const MessageCodec<Object?> pigeonChannelCodec = _PigeonCodec();

  void onAvailableReadersChanged(List<Reader?> readers);

  void onReaderConnectionStatusChanged(ReaderConnectionStatus status);

  void onTagsRead(List<RfidTag?> tags);

  void onBatteryDataReceived(BatteryData batteryData);

  static void setUp(FlutterZebraRfidCallbacks? api, {BinaryMessenger? binaryMessenger, String messageChannelSuffix = '',}) {
    messageChannelSuffix = messageChannelSuffix.isNotEmpty ? '.$messageChannelSuffix' : '';
    {
      final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onAvailableReadersChanged$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onAvailableReadersChanged was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final List<Reader?>? arg_readers = (args[0] as List<Object?>?)?.cast<Reader?>();
          assert(arg_readers != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onAvailableReadersChanged was null, expected non-null List<Reader?>.');
          try {
            api.onAvailableReadersChanged(arg_readers!);
            return wrapResponse(empty: true);
          } on PlatformException catch (e) {
            return wrapResponse(error: e);
          }          catch (e) {
            return wrapResponse(error: PlatformException(code: 'error', message: e.toString()));
          }
        });
      }
    }
    {
      final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onReaderConnectionStatusChanged$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onReaderConnectionStatusChanged was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final ReaderConnectionStatus? arg_status = (args[0] as ReaderConnectionStatus?);
          assert(arg_status != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onReaderConnectionStatusChanged was null, expected non-null ReaderConnectionStatus.');
          try {
            api.onReaderConnectionStatusChanged(arg_status!);
            return wrapResponse(empty: true);
          } on PlatformException catch (e) {
            return wrapResponse(error: e);
          }          catch (e) {
            return wrapResponse(error: PlatformException(code: 'error', message: e.toString()));
          }
        });
      }
    }
    {
      final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onTagsRead$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onTagsRead was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final List<RfidTag?>? arg_tags = (args[0] as List<Object?>?)?.cast<RfidTag?>();
          assert(arg_tags != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onTagsRead was null, expected non-null List<RfidTag?>.');
          try {
            api.onTagsRead(arg_tags!);
            return wrapResponse(empty: true);
          } on PlatformException catch (e) {
            return wrapResponse(error: e);
          }          catch (e) {
            return wrapResponse(error: PlatformException(code: 'error', message: e.toString()));
          }
        });
      }
    }
    {
      final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onBatteryDataReceived$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onBatteryDataReceived was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final BatteryData? arg_batteryData = (args[0] as BatteryData?);
          assert(arg_batteryData != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfidCallbacks.onBatteryDataReceived was null, expected non-null BatteryData.');
          try {
            api.onBatteryDataReceived(arg_batteryData!);
            return wrapResponse(empty: true);
          } on PlatformException catch (e) {
            return wrapResponse(error: e);
          }          catch (e) {
            return wrapResponse(error: PlatformException(code: 'error', message: e.toString()));
          }
        });
      }
    }
  }
}
