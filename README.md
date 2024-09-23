# flutter_zebra_rfid

Flutter plugin for Zebra RFID for iOS and Android


Notes:
- needs to be minSdkVersion 26
- Barcode library overwrites android:label so app manifest needs to be adjusted:
```
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
    android:name=".MainApplication"
    android:label="flutter_zebra_rfid_example"
    android:icon="@mipmap/ic_launcher"
    tools:replace="android:label">

    ...

```

iOS
- Enable Background modes: External accessory communication, Uses BLE accessories
- Supported external accessory protocols:

