# YCCU Capture

Phase 1 protocol-capture tool for Yamaha YCCU (Aerox Turbo / Y-Connect) media channel.

## Build
Push to `main` → GitHub Actions → Artifacts → `app-debug.apk`.

## Use
1. Install `app-debug.apk` (allow unknown sources).
2. Grant Bluetooth + notification permissions.
3. Tap "Scan & Connect" — the app finds a device whose name contains `YCCU`.
4. Press each bike button once, one every 3 seconds.
5. Tap "Save" to dump the log to `Android/data/com.example.yccucapture/files/`.

## What you'll see
- `[HH:mm:ss.SSS] h=0x000f len=32 6b 05 00 01 21 …` — status / button events
- `h=0x000d` should never appear here; that's write direction.

## Next
Send the saved log back, and Phase 2 (media control + metadata push) gets written on top.
