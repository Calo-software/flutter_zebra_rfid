# Capture Device orchestration

The plugin owns **Capture Device** orchestration above the separate Zebra RFID and barcode SDK APIs. Apps should be able to select one physical working setup, such as a phone with a Bluetooth combo reader or a TC22 docked into an RFID sled, and have the plugin connect the RFID Reader and Barcode Endpoint together while surfacing degraded per-capability status when only one side succeeds.
