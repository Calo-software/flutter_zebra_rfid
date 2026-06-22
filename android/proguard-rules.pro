# ProGuard rules for flutter_zebra_rfid plugin
# These rules will be automatically applied to apps using this plugin

# Keep BouncyCastle crypto classes required by Zebra Scanner SDK
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep Zebra RFID API3 SDK classes
-keep class com.zebra.rfid.api3.** { *; }
-dontwarn com.zebra.rfid.api3.**

# Keep Zebra Scanner Control classes
-keep class com.zebra.scannercontrol.** { *; }
-dontwarn com.zebra.scannercontrol.**

# Keep Zebra Barcode SDK classes
-keep class com.zebra.barcode.sdk.** { *; }
-dontwarn com.zebra.barcode.sdk.**

# Prevent stripping of native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Flutter plugin classes
-keep class nz.calo.flutter_zebra_rfid.** { *; }
