# TC22 Built-in RFID Reader Fix

## Problem Identified

The TC22 built-in RFID reader was working in Zebra's "123RFID Mobile" app but not in the Flutter plugin. This indicated a **code/SDK initialization issue**, not a hardware or configuration problem.

## Root Cause

The Zebra RFID SDK uses different transport types for different reader connections:
- `BLUETOOTH` - For external Bluetooth readers (RFD40, RFD90, etc.)
- `SERVICE_USB` - For external USB readers connected via OTG cable
- `SERVICE_SERIAL` - For **built-in RFID readers** in devices like TC22/TC27

The plugin was only using `SERVICE_USB` when the "USB" connection type was selected, which **missed built-in serial readers**.

## Solution

Modified [RFIDReaderInterface.kt](android/src/main/kotlin/nz/calo/flutter_zebra_rfid/rfid/RFIDReaderInterface.kt) to use `ENUM_TRANSPORT.ALL` when discovering USB readers. This ensures both external USB and built-in serial readers are discovered.

### Code Changes

```kotlin
fun getAvailableReaderList(connectionType: ReaderConnectionType) {
    val transport = when (connectionType) {
        ReaderConnectionType.BLUETOOTH -> ENUM_TRANSPORT.BLUETOOTH
        ReaderConnectionType.USB -> ENUM_TRANSPORT.ALL  // Changed: Include both USB and Serial
        ReaderConnectionType.ALL -> ENUM_TRANSPORT.ALL
    }
    
    if (readers == null || connectionType != currentConnectionType) {
        readers = Readers(applicationContext, transport)
    }
    // ... rest of discovery code
}
```

## Testing

1. Install the updated APK on your TC22
2. Open the app
3. Select connection type: **"USB"**
4. Tap **"Discover Readers"**
5. The built-in RFID reader should now appear in the list

## Technical Details

### Transport Type Mapping

| Connection Type (Flutter) | SDK Transport | Discovers |
|--------------------------|---------------|-----------|
| `bluetooth` | `BLUETOOTH` | External BT readers only |
| `usb` | `ALL` ⬅️ Fixed | External USB + Built-in Serial |
| `all` | `ALL` | All reader types |

### Why This Works

- Zebra's 123RFID app uses `ENUM_TRANSPORT.ALL` by default
- TC22/TC27 built-in readers use `SERVICE_SERIAL` transport internally
- Using `ALL` transport ensures we don't miss any connected readers
- No negative side effects - just discovers more readers

### Additional Debugging

Added logging to help diagnose reader discovery:
```kotlin
Log.d(TAG, "Available readers for $connectionType (transport=$transport): ...")
availableRFIDReaderList?.forEach { device ->
    Log.d(TAG, "Reader found: ${device.name}")
}
```

Check logs with:
```bash
adb logcat | grep FlutterZebraRfidPlugin
```

## What Doesn't Change

- USB permissions still required (already implemented)
- ProGuard rules still needed (already implemented)
- No device configuration needed (Settings, DataWedge, etc.)
- External readers (RFD40) work the same as before

## For Users

If you were only seeing your RFD40 Bluetooth reader but not the TC22 built-in reader:
1. Update to this version
2. Select "USB" connection type
3. Discover readers
4. Both should now appear!

---

**Files Modified:**
- `/android/src/main/kotlin/nz/calo/flutter_zebra_rfid/rfid/RFIDReaderInterface.kt`

**Build Status:** ✅ Successful
**APK Size:** 46.8MB
