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

enum ScannerConnectionType {
  bluetooth,
  usb,
}

enum ScannerConnectionStatus {
  connecting,
  connected,
  disconnecting,
  disconnected,
  error,
}

class BarcodeScanner {
  BarcodeScanner({
    this.name,
    required this.id,
    this.model,
    this.serialNumber,
  });

  String? name;

  int id;

  String? model;

  String? serialNumber;

  Object encode() {
    return <Object?>[
      name,
      id,
      model,
      serialNumber,
    ];
  }

  static BarcodeScanner decode(Object result) {
    result as List<Object?>;
    return BarcodeScanner(
      name: result[0] as String?,
      id: result[1]! as int,
      model: result[2] as String?,
      serialNumber: result[3] as String?,
    );
  }
}

class Barcode {
  Barcode({
    required this.data,
    required this.scannerId,
    this.barcodeType,
  });

  String data;

  int scannerId;

  int? barcodeType;

  Object encode() {
    return <Object?>[
      data,
      scannerId,
      barcodeType,
    ];
  }

  static Barcode decode(Object result) {
    result as List<Object?>;
    return Barcode(
      data: result[0]! as String,
      scannerId: result[1]! as int,
      barcodeType: result[2] as int?,
    );
  }
}


class _PigeonCodec extends StandardMessageCodec {
  const _PigeonCodec();
  @override
  void writeValue(WriteBuffer buffer, Object? value) {
    if (value is ScannerConnectionType) {
      buffer.putUint8(129);
      writeValue(buffer, value.index);
    } else     if (value is ScannerConnectionStatus) {
      buffer.putUint8(130);
      writeValue(buffer, value.index);
    } else     if (value is BarcodeScanner) {
      buffer.putUint8(131);
      writeValue(buffer, value.encode());
    } else     if (value is Barcode) {
      buffer.putUint8(132);
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
        return value == null ? null : ScannerConnectionType.values[value];
      case 130: 
        final int? value = readValue(buffer) as int?;
        return value == null ? null : ScannerConnectionStatus.values[value];
      case 131: 
        return BarcodeScanner.decode(readValue(buffer)!);
      case 132: 
        return Barcode.decode(readValue(buffer)!);
      default:
        return super.readValueOfType(type, buffer);
    }
  }
}

class FlutterZebraBarcode {
  /// Constructor for [FlutterZebraBarcode].  The [binaryMessenger] named argument is
  /// available for dependency injection.  If it is left null, the default
  /// BinaryMessenger will be used which routes to the host platform.
  FlutterZebraBarcode({BinaryMessenger? binaryMessenger, String messageChannelSuffix = ''})
      : pigeonVar_binaryMessenger = binaryMessenger,
        pigeonVar_messageChannelSuffix = messageChannelSuffix.isNotEmpty ? '.$messageChannelSuffix' : '';
  final BinaryMessenger? pigeonVar_binaryMessenger;

  static const MessageCodec<Object?> pigeonChannelCodec = _PigeonCodec();

  final String pigeonVar_messageChannelSuffix;

  /// Returns list with names of available readers for specified `connectionType`.
  Future<void> updateAvailableScanners() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcode.updateAvailableScanners$pigeonVar_messageChannelSuffix';
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

  /// Connects to a reader with `readerId` ID.
  Future<void> connectScanner(int scannerId) async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcode.connectScanner$pigeonVar_messageChannelSuffix';
    final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
      pigeonVar_channelName,
      pigeonChannelCodec,
      binaryMessenger: pigeonVar_binaryMessenger,
    );
    final List<Object?>? pigeonVar_replyList =
        await pigeonVar_channel.send(<Object?>[scannerId]) as List<Object?>?;
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

  /// Disconnects a current scanner.
  Future<void> disconnectScanner() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcode.disconnectScanner$pigeonVar_messageChannelSuffix';
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
  Future<BarcodeScanner?> currentScanner() async {
    final String pigeonVar_channelName = 'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcode.currentScanner$pigeonVar_messageChannelSuffix';
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
      return (pigeonVar_replyList[0] as BarcodeScanner?);
    }
  }
}

abstract class FlutterZebraBarcodeCallbacks {
  static const MessageCodec<Object?> pigeonChannelCodec = _PigeonCodec();

  void onAvailableScannersChanged(List<BarcodeScanner?> readers);

  void onScannerConnectionStatusChanged(ScannerConnectionStatus status);

  void onBarcodeRead(Barcode barcode);

  static void setUp(FlutterZebraBarcodeCallbacks? api, {BinaryMessenger? binaryMessenger, String messageChannelSuffix = '',}) {
    messageChannelSuffix = messageChannelSuffix.isNotEmpty ? '.$messageChannelSuffix' : '';
    {
      final BasicMessageChannel<Object?> pigeonVar_channel = BasicMessageChannel<Object?>(
          'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onAvailableScannersChanged$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onAvailableScannersChanged was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final List<BarcodeScanner?>? arg_readers = (args[0] as List<Object?>?)?.cast<BarcodeScanner?>();
          assert(arg_readers != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onAvailableScannersChanged was null, expected non-null List<BarcodeScanner?>.');
          try {
            api.onAvailableScannersChanged(arg_readers!);
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
          'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onScannerConnectionStatusChanged$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onScannerConnectionStatusChanged was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final ScannerConnectionStatus? arg_status = (args[0] as ScannerConnectionStatus?);
          assert(arg_status != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onScannerConnectionStatusChanged was null, expected non-null ScannerConnectionStatus.');
          try {
            api.onScannerConnectionStatusChanged(arg_status!);
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
          'dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onBarcodeRead$messageChannelSuffix', pigeonChannelCodec,
          binaryMessenger: binaryMessenger);
      if (api == null) {
        pigeonVar_channel.setMessageHandler(null);
      } else {
        pigeonVar_channel.setMessageHandler((Object? message) async {
          assert(message != null,
          'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onBarcodeRead was null.');
          final List<Object?> args = (message as List<Object?>?)!;
          final Barcode? arg_barcode = (args[0] as Barcode?);
          assert(arg_barcode != null,
              'Argument for dev.flutter.pigeon.flutter_zebra_barcode.FlutterZebraBarcodeCallbacks.onBarcodeRead was null, expected non-null Barcode.');
          try {
            api.onBarcodeRead(arg_barcode!);
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
