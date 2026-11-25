# KZEO Storm 4 Mod Manager (Android)

Android utility app designed for **Naruto Shippuden: Ultimate Ninja Storm 4 (PC Native Android Port / `com.naruto.storm4nativo`)** to extract, sanitize, auto-route, install, and repack Nexus Mods archives directly on Android without a PC.

## Features
- **Multi-Format Archive Support**: Extracts `.zip`, `.rar`, `.7z`, and loose `.xfbin` / `.nut` / `.awb` files.
- **Smart Auto-Router**: Recursively locates `data_win32` / `data` folders or automatically routes loose CyberConnect2 `.xfbin` files (`*bod1.xfbin`, `*awa.xfbin`, `charsel*`, etc.) to their proper target directories.
- **PC Binary Sanitizer**: Automatically filters out incompatible PC `.dll`, `.exe`, `ModdingAPI`, and `ReShade` files that cause crashes on Android.
- **Dual Output Modes**:
  1. **Direct Install (`data_win32`)**: Merges mod files straight into your Storm 4 directory with automatic timestamped backups and 1-tap restore/uninstall.
  2. **Launcher Repack (`.zip` + `mod.json`)**: Generates clean root-level `data_win32/` `.zip` packages inside `/Download/Storm4_Ready_Mods/` compatible with the native Storm 4 Launcher's built-in Mod Importer.

## Build on GitHub
1. Create a new repository on GitHub and upload all files from this project.
2. Go to the **Actions** tab and run **Build Storm 4 Mod Manager APK** (or push to `main`).
3. Download the compiled `.apk` from **Artifacts**.
