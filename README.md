<img width="3500" height="2000" alt="1000894281" src="https://github.com/user-attachments/assets/4bf8c90f-6ed1-4a13-ab6b-c3a8954fc092" />


# WOlfhound3D

**Native Android port of Wolf4SDL**

WOlfhound3D is a fully native Android port of the classic *Wolfenstein 3D* / *Spear of Destiny* engine based on **Wolf4SDL**.  
It uses **SDL2** + **SDL2_mixer** (statically linked) and adds modern mobile-friendly features: on-screen touch controls, automatic game-data handling, immersive fullscreen, and landscape orientation.

The project is designed to keep the original pixel-perfect feel of Wolf4SDL while making it comfortable to play on modern Android devices.

---

## Features

- Native ARM64 (`arm64-v8a`) build
- SDL2 + SDL2_mixer statically linked (no external shared libraries)
- Custom on-screen touch controls (v2)
  - Floating virtual stick (left side) – move + strafe
  - Swipe-to-look (right side)
  - Aim-while-shooting (slide finger while holding FIRE)
  - FIRE / USE / RUN (toggle) / MENU / weapon keys 1-4
- Automatic game data detection & copying
- Immersive fullscreen (system bars hidden, display cutout support)
- Landscape orientation only (both left & right)
- Accelerometer joystick disabled (prevents unwanted menu/player movement)
- Touch events do **not** generate mouse clicks (no accidental firing)
- Supports the most common Wolfenstein data sets:
  - `.wl6` (full Wolfenstein 3D)
  - `.wl1` (shareware)
  - `.sod` / `.sdm` (Spear of Destiny)
  - `.wl3` (3-episode version)

---

## Game Data

The engine needs the original game files:

```
vswap.*   maphead.*   gamemaps.*
audiohed.*   audiot.*
vgagraph.*   vgahead.*   vgadict.*
```

(and optionally `HELPART.*`, `ENDART1.*`, `DEMO0.*`, etc.)

### Two ways to provide the data

1. **External storage (recommended for users)**  
   Copy the files into:
   ```
   Android/data/com.wolfhound.canavar/files/
   ```
   The app will detect them automatically on the next launch.

2. **Bundled in the APK (for developers)**  
   Place the files into:
   ```
   app/src/main/assets/
   ```
   On first launch (or after an update) the app copies them into its private storage.

The first location that contains a valid `vswap.*` file is used.  
File names are automatically normalized to the case the engine expects.

If no data is found, a clear dialog is shown explaining both options.

---

## Touch Controls (v2)

| Area / Button          | Action                                      |
|------------------------|---------------------------------------------|
| Left half of screen    | Floating stick (appears under your finger)  |
| Stick up / down        | Walk forward / backward                     |
| Stick left / right     | Strafe                                      |
| Right half of screen   | Swipe left/right → turn camera              |
| FIRE button            | Shoot                                       |
| Hold FIRE + slide      | Aim while shooting                          |
| USE                    | Open doors / use                            |
| RUN                    | Toggle run mode (blue = on)                 |
| MENU (≡)               | Escape / pause menu                         |
| 1 – 4                  | Select weapon                               |

Multi-touch is fully supported (stick + buttons work simultaneously).

---

## Building

### Requirements

- Android Studio (or command-line Android SDK + NDK)
- NDK 29.x (or compatible)
- CMake 3.22+
- Minimum SDK 21, Target SDK 35
- Only `arm64-v8a` is currently enabled

### Build steps

1. Clone the repository.
2. (Optional) Put game data files into `app/src/main/assets/`.
3. Open the project in Android Studio **or** run:
   ```bash
   ./gradlew assembleDebug
   # or
   ./gradlew assembleRelease
   ```
4. Install the resulting APK.

The native code is built with CMake (`app/src/main/cpp/CMakeLists.txt`).  
SDL2 and SDL2_mixer are compiled as static libraries and linked into a single `libmain.so`.

---

## Project Structure

```
app/
├── src/main/
│   ├── java/com/wolfhound/canavar/
│   │   ├── MainActivity.java          # Data preparation + touch overlay
│   │   ├── TouchControls.java         # On-screen controls
│   │   ├── WolfhoundSDLActivity.java  # SDLActivity subclass
│   │   └── sdl/                       # SDL Java bindings
│   ├── cpp/
│   │   ├── main.cpp                   # Android entry point + JNI
│   │   ├── CMakeLists.txt
│   │   ├── SDL2/                      # SDL2 source
│   │   ├── SDL2_mixer/                # SDL2_mixer source
│   │   └── wolf4sdl/                  # Wolf4SDL engine sources
│   ├── assets/                        # Optional bundled game data
│   └── AndroidManifest.xml
└── build.gradle
```

### Key implementation details

- **main.cpp**  
  - Finds the correct data directory  
  - Sets `HOME` so config/saves work  
  - Forces landscape orientation  
  - Disables touch→mouse and accelerometer→joystick  
  - Exposes JNI methods for touch controls

- **Touch controls**  
  Java overlay (`TouchControls`) draws the UI and sends events to the native side via JNI (`nativeKey`, `nativeLook`, `nativeStrafe`).  
  The engine side (`wl_play.cpp`) implements `Touch_AddLook`, `Touch_SetStrafe` and `Touch_InGameplay`.

- **Data handling**  
  `MainActivity` checks external storage first, then copies from assets (with a stamp file so updates are detected).

---

## Supported Data Versions

| Extension | Game                          |
|-----------|-------------------------------|
| `.wl6`    | Wolfenstein 3D (full)         |
| `.wl1`    | Wolfenstein 3D (shareware)    |
| `.wl3`    | Wolfenstein 3D (3 episodes)   |
| `.sod`    | Spear of Destiny              |
| `.sdm`    | Spear of Destiny (demo)       |

---

## Credits

- **Wolf4SDL** by Moritz “Ripper” Kroll and contributors  
  Original project: https://github.com/fabiangreffrath/wolf4sdl
- **SDL2** & **SDL2_mixer** by the SDL team
- Original *Wolfenstein 3D* by id Software

---

## License

This project is based on Wolf4SDL, which is released under the **GNU GPL v2** (or later).  
SDL2 and SDL2_mixer have their own licenses (zlib / MIT-style).

You still need a legal copy of the original *Wolfenstein 3D* / *Spear of Destiny* data files to play the full games.

---

## Status

This is an early but fully playable native Android port.  
Feel free to open issues or pull requests if you find bugs or want to improve the controls / features.
```
