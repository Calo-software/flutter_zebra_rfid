# Debug Logging Guide

## Enhanced Logging Now Available

The plugin now includes comprehensive debug logging to help diagnose TC22 reader discovery and connection issues.

## How to View Logs

### Method 1: Real-time Logcat (Recommended)

Connect your TC22 via USB and run:

```bash
adb logcat -s FlutterZebraRfidPlugin:*
```

This shows ONLY the plugin logs, filtered by priority:
- **I** = Info (important events)
- **D** = Debug (detailed flow)
- **W** = Warning (potential issues)
- **E** = Error (failures)

### Method 2: Save Logs to File

```bash
adb logcat -s FlutterZebraRfidPlugin:* > rfid_debug.log
```

Then open `rfid_debug.log` in your text editor.

### Method 3: Focused View

For just reader discovery:
```bash
adb logcat -s FlutterZebraRfidPlugin:* | grep "READER DISCOVERY"
```

For just connection attempts:
```bash
adb logcat -s FlutterZebraRfidPlugin:* | grep "CONNECT READER"
```

For errors only:
```bash
adb logcat -s FlutterZebraRfidPlugin:E
```

## What to Look For

### During Reader Discovery

When you tap "Discover Readers", you should see:

```
I FlutterZebraRfidPlugin: ========== READER DISCOVERY STARTED ==========
I FlutterZebraRfidPlugin: Requested connection type: USB
I FlutterZebraRfidPlugin: Using SDK transport: ALL
D FlutterZebraRfidPlugin: Creating new Readers instance with transport: ALL
D FlutterZebraRfidPlugin: Calling GetAvailableRFIDReaderList()...
I FlutterZebraRfidPlugin: Discovery complete. Found 1 reader(s)
I FlutterZebraRfidPlugin: --- Reader #0 ---
I FlutterZebraRfidPlugin:   Name: RFD40-XXXXX   <-- Your reader name
I FlutterZebraRfidPlugin:   RFIDReader: [object details]
```

**If you see `Found 0 reader(s)`, the logs will show diagnostic hints.**

### During Connection

When you tap a reader to connect:

```
I FlutterZebraRfidPlugin: ========== CONNECT READER STARTED ==========
I FlutterZebraRfidPlugin: Requested reader ID: 0
I FlutterZebraRfidPlugin: Selected reader device: RFD40-XXXXX
D FlutterZebraRfidPlugin: RFIDReader object obtained: [object]
I FlutterZebraRfidPlugin: Reader not connected, starting connection sequence...
D FlutterZebraRfidPlugin: Launching blocking connect() call on background thread...
D FlutterZebraRfidPlugin: Calling targetReader.connect()...
I FlutterZebraRfidPlugin: targetReader.connect() completed successfully!
```

**If connection fails, detailed error info will be logged.**

## Common Log Patterns

### ✅ Success Pattern
```
I: Discovery complete. Found 1 reader(s)
I: --- Reader #0 ---
I: Selected reader device: [name]
I: targetReader.connect() completed successfully!
I: Reader connected (attempt #1)
```

### ❌ No Readers Found
```
I: Discovery complete. Found 0 reader(s)
W: WARNING: No readers found!
W:   - Connection type requested: USB
W:   - Transport used: ALL
W:   - If using TC22 built-in RFID, verify:
W:     1. Device actually has RFID hardware (not all TC22s do)
W:     2. RFID works in Zebra's 123RFID Mobile app
W:     3. Check Settings → RFID is enabled
```

### ❌ Connection Error
```
E: OperationFailureException during connect: [message]
E:   Vendor message: [details]
E:   Status description: [details]
```

## Testing Steps

1. **Install the updated APK** on your TC22

2. **Start logging** in one terminal:
   ```bash
   adb logcat -s FlutterZebraRfidPlugin:* | tee rfid_debug.log
   ```

3. **Run your app** and try to discover readers

4. **Check the logs** for the patterns above

5. **Share the logs** if still having issues (attach `rfid_debug.log`)

## Debug Flag

The debug flag is currently set to `true` in the code:

```kotlin
private val DEBUG = true // Enable verbose logging
```

To disable extra verbose logging later, change to:
```kotlin
private val DEBUG = false
```

Then rebuild the app.

## What the Logs Will Tell Us

The enhanced logging will reveal:

1. **Is reader discovery being called?** (Look for "READER DISCOVERY STARTED")
2. **What transport type is being used?** (Should be "ALL" for USB)
3. **How many readers were found?** (Should be > 0 for TC22)
4. **What are the reader names?** (TC22 built-in vs external)
5. **Is connection being attempted?** (Look for "CONNECT READER STARTED")
6. **Where does connection fail?** (Exact exception and details)

## Next Steps

After running with logs:

1. **If 0 readers found:**
   - Verify TC22 model has RFID (check device label)
   - Confirm 123RFID Mobile app can see the reader
   - Check Settings → RFID is enabled
   - Share the discovery logs

2. **If readers found but connection fails:**
   - Note the exact error message
   - Note the "Vendor message" or "Status description"
   - Share the connection logs

3. **If it works:**
   - Great! You can disable DEBUG flag if desired

---

**The logs are your friend!** They'll show exactly what's happening at each step.
