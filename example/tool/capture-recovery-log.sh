#!/usr/bin/env bash
# Usage: capture-recovery-log.sh ADB_SERIAL [OUTPUT_DIRECTORY]
# Start before launching the example; Ctrl-C stops capture. Does not clear logs.
set -euo pipefail
serial=${1:?Pass the TC22 adb serial}
output=${2:-recovery-logs/$(date -u +%Y%m%dT%H%M%SZ)}
mkdir -p "$output"
adb -s "$serial" get-state
adb -s "$serial" shell getprop > "$output/device-properties.txt"
git rev-parse HEAD > "$output/plugin-head.txt"
git diff --binary > "$output/local-changes.patch"
# Capture every process: the vendor serial-worker crash can outlive the app PID.
exec adb -s "$serial" logcat -b all -v threadtime > "$output/logcat.txt" 2>&1
