# Capture Device Manual Test Matrix

Use the example app's **Capture** tab to verify that one Capture Device manages both RFID and barcode capability setup.

## Android EM45 RFID integrated mobile computer

- Confirm the label or **Settings → About phone** identifies the RFID-capable EM45 variant, and record the Android build.
- Connect over ADB and confirm Zebra 123RFID Mobile can inventory a tag before installing the example app.
- In **Settings → Key Programmer**, map **RIGHT_TRIGGER_2** to **SYMBOL_TRIGGER_6**.
- Open **Capture**, tap **Refresh Capture Devices**, and confirm one **Integrated mobile computer** groups the EM45 RFID Reader with `datawedge:INTERNAL_CAMERA`.
- Tap **Connect**, verify RFID and barcode capabilities both become connected, and capture logs showing the EM45 host model, local RFID transport, and camera endpoint.
- Perform at least 20 trigger press/release cycles. Every press must start RFID inventory and every release must stop it without watchdog recovery.
- Scan a barcode through the internal camera, alternate RFID and barcode operations, and confirm neither stream receives duplicate or cross-routed reads.
- Exercise locate, regulatory-region handling, app relaunch, disconnect, and reconnect before declaring the hardware gate passed.

## Android phone + Bluetooth combo reader

- Pair the Zebra Bluetooth reader in Android Bluetooth settings or the example app pairing flow.
- Open **Capture**, tap **Refresh Capture Devices**, and confirm one Capture Device appears for the Bluetooth reader.
- Confirm the Capture Device shows RFID and barcode capabilities, with a match reason that references shared identifiers or model/name tokens.
- Tap **Connect** and confirm status becomes **Connected** or **Degraded** with per-capability details.
- Pull the reader trigger and confirm the dashboard updates both the last RFID tag and last barcode when the hardware supports both.

## iOS phone + Bluetooth combo reader

- Pair the Zebra reader using the supported iOS/Zebra accessory flow outside the plugin.
- Open **Capture**, tap **Refresh Capture Devices**, and confirm the external reader appears when Zebra's RFID and Scanner SDKs report it.
- Confirm no iOS Bluetooth pairing helper is expected in this workflow.
- Tap **Connect** and confirm RFID and barcode capability status updates independently.
- Scan RFID tags and barcodes, then confirm the dashboard shows the latest read evidence.

## TC22 docked into RFID sled

- Dock the TC22 into the RFID sled and verify the terminal's built-in barcode scanner is enabled in DataWedge.
- Open **Capture**, tap **Refresh Capture Devices**, and confirm one Capture Device groups the sled RFID Reader with the built-in terminal Barcode Endpoint.
- Confirm topology shows **TC22 RFID sled** or **External RFID + terminal barcode**.
- Tap **Connect** and confirm the plugin does not require separate app-level RFID and barcode connection flows.
- Scan an RFID tag with the sled and a barcode with the TC22 imager; confirm both latest-read fields update on the dashboard.
- If the wrong barcode path is selected, use **Barcode Override**, reconnect, and confirm the match changes to **Manual match**.
