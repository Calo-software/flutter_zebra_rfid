# Capture Device Manual Test Matrix

Use the example app's **Capture** tab to verify that one Capture Device manages both RFID and barcode capability setup.

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
