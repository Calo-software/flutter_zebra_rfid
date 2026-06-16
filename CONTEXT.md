# Flutter Zebra RFID

Flutter plugin context for Zebra data capture hardware. The plugin wraps Zebra SDKs so apps can treat RFID and barcode capture as one operational device when the hardware exposes them through separate SDK paths.

## Language

**Capture Device**:
A user-facing physical working setup that may provide RFID capture, barcode capture, or both.
_Avoid_: Device, reader/scanner pair

**RFID Reader**:
The Zebra RFID capability that discovers and reads RFID tags.
_Avoid_: RFID device, tag scanner

**Barcode Scanner**:
The Zebra barcode capability that reads barcode data.
_Avoid_: Imager, barcode reader

**Barcode Endpoint**:
A selectable barcode input path exposed by DataWedge or the Zebra Scanner SDK.
_Avoid_: Scanner when referring to a DataWedge path

**Capability**:
One capture function, such as RFID or barcode, within a Capture Device.
_Avoid_: Feature, module

**Topology**:
The physical arrangement that determines where RFID and barcode capabilities live.
_Avoid_: Mode, setup

**Capture Device Plan**:
A computed view of available Capture Devices from RFID Reader snapshots, Barcode Endpoint snapshots, active capability state, and manual Barcode Endpoint overrides.
_Avoid_: Device list, scanner matching result

## Relationships

- A **Capture Device** has one optional **RFID Reader** capability and one optional **Barcode Endpoint** capability.
- A **Barcode Scanner** may expose one or more **Barcode Endpoints**.
- A **Topology** explains why a **Capture Device** may need multiple SDK connections.
- A **Capture Device Plan** assigns each Barcode Endpoint to at most one Capture Device unless a manual override changes that assignment.

## Example dialogue

> **Dev:** "When the operator selects the TC22 sled, should the app connect the RFID reader and barcode scanner separately?"
> **Domain expert:** "No - the operator selected one Capture Device. The plugin should connect the sled RFID Reader and activate the TC22 Barcode Endpoint together."

## Flagged ambiguities

- "device" was used for phones, sleds, RFID readers, barcode scanners, and full working setups. Resolved: use **Capture Device** for the user-facing working setup, and **RFID Reader** or **Barcode Endpoint** for lower-level capabilities.
