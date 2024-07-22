// Autogenerated from Pigeon (v21.0.0), do not edit directly.
// See also: https://pub.dev/packages/pigeon

import Foundation

#if os(iOS)
  import Flutter
#elseif os(macOS)
  import FlutterMacOS
#else
  #error("Unsupported platform.")
#endif

/// Error class for passing custom error details to Dart side.
final class PigeonError: Error {
  let code: String
  let message: String?
  let details: Any?

  init(code: String, message: String?, details: Any?) {
    self.code = code
    self.message = message
    self.details = details
  }

  var localizedDescription: String {
    return
      "PigeonError(code: \(code), message: \(message ?? "<nil>"), details: \(details ?? "<nil>")"
      }
}

private func wrapResult(_ result: Any?) -> [Any?] {
  return [result]
}

private func wrapError(_ error: Any) -> [Any?] {
  if let pigeonError = error as? PigeonError {
    return [
      pigeonError.code,
      pigeonError.message,
      pigeonError.details,
    ]
  }
  if let flutterError = error as? FlutterError {
    return [
      flutterError.code,
      flutterError.message,
      flutterError.details,
    ]
  }
  return [
    "\(error)",
    "\(type(of: error))",
    "Stacktrace: \(Thread.callStackSymbols)",
  ]
}

private func isNullish(_ value: Any?) -> Bool {
  return value is NSNull || value == nil
}

private func nilOrValue<T>(_ value: Any?) -> T? {
  if value is NSNull { return nil }
  return value as! T?
}
private class ZebraRfidApiPigeonCodecReader: FlutterStandardReader {
}

private class ZebraRfidApiPigeonCodecWriter: FlutterStandardWriter {
}

private class ZebraRfidApiPigeonCodecReaderWriter: FlutterStandardReaderWriter {
  override func reader(with data: Data) -> FlutterStandardReader {
    return ZebraRfidApiPigeonCodecReader(data: data)
  }

  override func writer(with data: NSMutableData) -> FlutterStandardWriter {
    return ZebraRfidApiPigeonCodecWriter(data: data)
  }
}

class ZebraRfidApiPigeonCodec: FlutterStandardMessageCodec, @unchecked Sendable {
  static let shared = ZebraRfidApiPigeonCodec(readerWriter: ZebraRfidApiPigeonCodecReaderWriter())
}


/// Generated protocol from Pigeon that represents a handler of messages from Flutter.
protocol FlutterZebraRfid {
  func getAvailableReaders(completion: @escaping (Result<[String], Error>) -> Void)
}

/// Generated setup class from Pigeon to handle messages through the `binaryMessenger`.
class FlutterZebraRfidSetup {
  static var codec: FlutterStandardMessageCodec { ZebraRfidApiPigeonCodec.shared }
  /// Sets up an instance of `FlutterZebraRfid` to handle messages through the `binaryMessenger`.
  static func setUp(binaryMessenger: FlutterBinaryMessenger, api: FlutterZebraRfid?, messageChannelSuffix: String = "") {
    let channelSuffix = messageChannelSuffix.count > 0 ? ".\(messageChannelSuffix)" : ""
    let getAvailableReadersChannel = FlutterBasicMessageChannel(name: "dev.flutter.pigeon.flutter_zebra_rfid.FlutterZebraRfid.getAvailableReaders\(channelSuffix)", binaryMessenger: binaryMessenger, codec: codec)
    if let api = api {
      getAvailableReadersChannel.setMessageHandler { _, reply in
        api.getAvailableReaders { result in
          switch result {
          case .success(let res):
            reply(wrapResult(res))
          case .failure(let error):
            reply(wrapError(error))
          }
        }
      }
    } else {
      getAvailableReadersChannel.setMessageHandler(nil)
    }
  }
}
