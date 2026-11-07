# FX35 Diag

Android diagnostic app for a 2011 Infiniti FX35 AWD over an ELM327 Bluetooth Classic adapter.

Tabs: Connect, Live (generic OBD-II), Codes (generic mode 03/04), Modules (scan, read DTCs, identify,
clear DTCs per module), Console (read-style raw requests).

## Build with GitHub
1. Create a repo and push the contents of this folder to the main branch.
2. Open the Actions tab, run "Build APK" (it also runs on every push).
3. Download the FX35Diag-debug-apk artifact, unzip, and sideload the APK.

## Notes
- Ignition ON, engine off for module scans. Keep the battery healthy.
- Module addresses are discovered at runtime, not hardcoded. Module scan and per-module requests
  (UDS 0x19, KWP 0x18, 0x14, 0x22, 0x1A) have not been tested against a real car.
- Not included: Nissan security access, active tests, work-support routines, reprogramming.
- Cheap ELM327 clones vary a lot. A genuine or high-quality adapter helps.
