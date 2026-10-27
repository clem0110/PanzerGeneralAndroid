# Panzer General for Android

An authentic Android port and faithful recreation of SSI's classic 1994 turn-based tactical wargame **Panzer General** (PG-DOS / PG-Budapest v1.2 edition).

## Features

- **38 Scenarios & Full 1939–1945 Campaign**: Complete historical scenarios from Poland and Norway to Barbarossa, Stalingrad, D-Day, and Berlin.
- **Full Equipment Encyclopedia**: 425+ authentic units including Infantry, Tanks, Artillery, Air Defense, Fighters, Tactical/Level Bombers, and Naval Warships.
- **Accurate Rules & Combat Engine**:
  - Combined-arms combat mechanics with initiative caps, close defense terrain, rugged defense, and defensive artillery/air support.
  - Authentic initiative calculation with first-strike advantages.
  - Supply, ammo, and fuel consumption rules.
  - Dynamic weather and ground conditions (Clear, Overcast, Rain, Snow, Mud, Frozen).
  - Zone of Control (ZOC) and Fog of War (FOW) line-of-sight spotting.
  - Sea transport embarkation/disembarkation, naval movement, and air drops.
  - Replacements (Regular & Elite Overstrength up to 15 strength points).
  - Tank overruns and unit retreats/surrenders.
- **Retro SSI DOS Visuals & Audio**: Native sprite decoding from SSI `.SHP` shape files, authentic tile-based hex rendering, and sound effects.
- **Mobile Touch Controls**: Smooth panning, pinch-to-zoom hex map, tactile bottom status bar, combat prediction dialogs, unit purchase & deployment menus, and undo move support.

## Building and Installing

### Fast Standalone Build (PowerShell)
You can build a signed release debug APK directly using Android SDK Build Tools and JDK:

```powershell
.\build_apk.ps1
```

The compiled APK will be output to `app\build\outputs\apk\debug\PanzerGeneral-debug.apk`.

### Running on Device or Emulator
Launch via the automated batch script:
```cmd
Launch_Panzer_General.bat
```
Or manually via ADB:
```bash
adb install -r app/build/outputs/apk/debug/PanzerGeneral-debug.apk
adb shell am start -n com.ssi.panzergeneral/.MainActivity
```
