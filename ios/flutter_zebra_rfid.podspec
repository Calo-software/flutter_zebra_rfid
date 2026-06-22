#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_zebra_rfid.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_zebra_rfid'
  s.version          = '0.0.1'
  s.summary          = 'Flutter Zebra RFID SDK plugin'
  s.description      = <<-DESC
A new Flutter plugin project.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*.{h,m,swift}'
  s.dependency 'Flutter'
  s.platform = :ios, '12.0'
  s.vendored_frameworks = [
    'zebra-sdk/ZebraRfidSdkFramework.xcframework',
    'zebra-sdk/ZebraScannerFramework.xcframework',
  ]
  s.public_header_files = ['Classes/**/*.h' ]
  s.ios.frameworks = 'CoreBluetooth', 'ExternalAccessory'
  s.library = 'z'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
