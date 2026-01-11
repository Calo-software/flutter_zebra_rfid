# USB Reader Connection Troubleshooting Guide

## Quick Diagnosis

**Choose your scenario:**

| You have... | Connection Type | First check... |
|------------|-----------------|----------------|
| TC22/TC27 with built-in RFID | USB/Internal | [Check Settings → RFID enabled](#issue-no-readers-found-tc22tc27-built-in-rfid) ⚠️ MOST COMMON |
| RFD40/RFD90 Bluetooth sled | Bluetooth | Pair device, grant BT permissions |
| External reader via OTG cable | USB | Grant USB permissions, check cable |
| Unknown/Not working | All | Read through all sections below |

**TL;DR for TC22/TC27 users:** Your built-in RFID may be disabled in Settings, or your TC22 model may not have RFID hardware. Check device Settings → RFID (NOT DataWedge - that's for barcodes only).

---

## Understanding Zebra Reader Types

Before troubleshooting, it's important to understand what type of RFID reader you're using:

### Built-in RFID Readers (TC22, TC27, etc.)
- **Connection Type:** USB/Internal
- **Setup Required:** YES - Must be enabled via DataWedge/EMDK
- **Common Issue:** Reader disabled by default
- **How to Enable:** See "TC22/TC27 Built-in RFID" section below

### External Handheld Readers (RFD40, RFD90, etc.)
- **Connection Type:** Bluetooth
- **Setup Required:** Pairing only
- **Common Issue:** Bluetooth permissions
- **How to Connect:** Standard Bluetooth pairing

### External Fixed Readers via OTG
- **Connection Type:** USB (via OTG cable)
- **Setup Required:** USB permissions
- **Common Issue:** OTG cable/adapter compatibility
- **How to Connect:** Plug in via OTG, grant USB permission

---

## Issues Fixed

I've identified and fixed two critical issues preventing USB reader connections:

### 1. **Missing USB Permissions** ✅ FIXED
The Android manifest files were missing critical USB permissions and declarations.

**Changes made:**
- Added `android.permission.USB_PERMISSION` to both plugin and example manifests
- Added `android.hardware.usb.host` feature declaration
- Created USB device filter (`device_filter.xml`) for Zebra devices
- Added USB device attached intent filters

### 2. **Missing ProGuard/R8 Rules** ✅ FIXED
The Zebra SDK requires BouncyCastle crypto classes that were being stripped during release builds.

**Changes made:**
- Created ProGuard rules files (`proguard-rules.pro`) for both plugin and example app
- Added keep rules for BouncyCastle, Zebra RFID API3, and Scanner Control classes
- Configured plugin to automatically apply these rules to consumer apps via `consumerProguardFiles`

## Files Modified

1. `/example/android/app/src/main/AndroidManifest.xml` - Added USB permissions and intent filters
2. `/android/src/main/AndroidManifest.xml` - Added USB permissions to plugin
3. `/example/android/app/src/main/res/xml/device_filter.xml` - Created USB device filter
4. `/example/android/app/proguard-rules.pro` - Created ProGuard rules for example app
5. `/android/proguard-rules.pro` - Created consumer ProGuard rules for plugin
6. `/example/android/app/build.gradle` - Enabled minification and ProGuard
7. `/android/build.gradle` - Added consumerProguardFiles configuration

## Testing Steps

1. **Clean and rebuild the project:**
   ```bash
   cd example
   flutter clean
   flutter pub get
   flutter build apk
   ```

2. **Connect your Zebra USB reader** to the Android device via OTG cable

3. **Run the app:**
   ```bash
   flutter run
   ```

4. **In the app:**
   - Select connection type "USB" or "All"
   - Tap "Discover Readers"
   - You should see a USB permission dialog if this is the first time
   - Grant USB permission
   - The USB reader should appear in the list
   - Tap to connect

## Common USB Connection Issues

### Issue: No readers found (TC22/TC27 Built-in RFID)
**This is the most common issue with Zebra TC22/TC27 devices with built-in RFID readers.**

**Symptoms:**
- Only see external Bluetooth readers (like RFD40)
- Built-in USB/internal RFID reader not detected
- SDK returns empty reader list for USB connection type

**Root Cause:**
The TC22/TC27's built-in RFID reader may be disabled in device settings, or your TC22 model may not include RFID hardware (not all TC22s have built-in RFID).

**Solution - Enable TC22/TC27 RFID Reader:**

**IMPORTANT:** DataWedge is for barcode scanning only, NOT for RFID configuration.

1. **Check Device Settings:**
   - Settings → Device Settings → RFID (path may vary)
   - Settings → System → Advanced → RFID
   - Settings → Zebra Settings → RFID
   - Enable RFID if there's a toggle
   - Reboot device

2. **Verify Your Device Has RFID:**
   - Not all TC22 models include built-in RFID
   - Check device label/specs for "RFID" designation
   - Try running Zebra's "123RFID Mobile" app to test

3. **Check MDM/Enterprise Restrictions:**
   - Some organizations disable RFID via MDM
   - Contact your IT admin if in enterprise environment

4. **Test with Zebra's RFID App:**
   - Install "123RFID Mobile" from Play Store
   - If it can't see the reader, it's a hardware/OS issue
   - If it works there, check your Flutter app's SDK initialization

**After enabling:**
- Reboot the TC22/TC27
- Run your Flutter app
- Select "USB" or "All" connection type
- Built-in reader should appear as USB/Internal device

**Note:** External readers (RFD40, RFD90) connected via Bluetooth don't require any device configuration and will appear automatically when Bluetooth permissions are granted.

---

### Issue: No readers found (External USB via OTG)
**Possible causes:**
- USB cable not properly connected
- OTG adapter not working
- Device doesn't support USB host mode
- USB permissions not granted

**Solutions:**
- Verify the USB cable and OTG adapter work (test with another device)
- Check that your Android device supports USB host mode
- Manually grant USB permission in Android Settings > Apps > Your App > Permissions
- Try unplugging and replugging the USB reader

### Issue: Connection timeout
**Possible causes:**
- Reader not powered
- Wrong reader model/incompatible firmware
- USB bandwidth issues

**Solutions:**
- Ensure reader has adequate power (some readers need external power)
- Check Zebra SDK compatibility with your reader model
- Close other USB-intensive apps
- Check diagnostics: `await api.diagnostics()`

### Issue: Permission dialog doesn't appear
**Possible causes:**
- USB permissions already denied
- Manifest not properly updated
- Device filter doesn't match your reader

**Solutions:**
- Clear app data and reinstall
- Verify `device_filter.xml` vendor ID matches your Zebra reader (default: 2655)
- Check logcat for USB-related errors: `adb logcat | grep -i usb`

## Zebra USB Vendor ID

The device filter uses vendor ID `2655` (0x0A5F in hex) which is Zebra Technologies' USB vendor ID. If you have a different Zebra reader, you may need to update `/example/android/app/src/main/res/xml/device_filter.xml` with the specific product ID:

```xml
<usb-device vendor-id="2655" product-id="YOUR_PRODUCT_ID" />
```

To find your device's product ID:
```bash
# Connect reader and run:
adb shell
lsusb
# Look for Zebra device and note the product ID
```

## Debugging Commands

```bash
# View all USB logs
adb logcat | grep -i usb

# View app logs
adb logcat | grep FlutterZebraRfid

# Check USB devices
adb shell ls /dev/bus/usb/

# Verify permissions
adb shell pm list permissions | grep -i usb
adb shell dumpsys package YOUR_PACKAGE_NAME | grep -i usb
```

## Code-Level Debugging

The app includes diagnostics. After a failed connection, call:

```dart
final diag = await _flutterZebraRfidApi.diagnostics();
print('Connection State: ${diag.connectionState}');
print('Connect Attempts: ${diag.connectAttempts}');
print('Last Error: ${diag.lastErrorCode} - ${diag.lastErrorMessage}');
```

Error codes to watch for:
- `NO_AVAILABLE_READERS` - No USB readers detected
- `TIMEOUT` - Connection took too long (10s default)
- `SDK_OPERATION_FAILURE` - Zebra SDK error (check details)
- `SDK_INVALID_USAGE` - API called in wrong order

## Next Steps

1. Rebuild the app with the fixes
2. Test USB discovery with your reader
3. If issues persist, check logcat output and share errors
4. Verify your specific Zebra reader model is supported by the SDK version in use

## Need More Help?

If you continue experiencing issues:
1. Share the output of `adb logcat | grep -E "(FlutterZebra|USB|RFID)"`
2. Share the reader model number
3. Share the diagnostics output from `api.diagnostics()`
4. Note the Android version and device model
