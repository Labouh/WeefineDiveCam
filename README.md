# Weefine Dive Cam

An Android camera app for diving with a phone inside a **Weefine Bluetooth smartphone housing**.
The housing's physical buttons drive the camera over Bluetooth Low Energy, its depth and
temperature sensor feeds a live HUD, and photos are colour-corrected for the blue/green cast
of the water.

## Features

- **Housing remote control (BLE).** The app scans for devices whose name contains `WEEFINE`,
  connects, and maps the housing buttons:

  | Button        | Action                                           |
  |---------------|--------------------------------------------------|
  | Shutter       | Take a photo / start or stop a video             |
  | Mode / Back   | Switch between photo and video                   |
  | Zoom / +      | Zoom in                                          |
  | Filter / −    | Zoom out                                         |
  | Menu / OK     | Toggle the colour correction                     |
  | MF / AF       | Deep sleep (screen and camera off)               |

  The app keeps reconnecting to BLE in the background (the housing could be turned on/off with
  the phone inside, it will reconnect automatically).

- **Dive HUD.** Depth, water temperature, dive time, photo/video mode, filter state, and the
  housing and phone battery levels, on one line so the frame stays clear.

- **Underwater colour correction.** Below 0.3 m, the app rebuilds the red that the water
  absorbed, as `max(R, G − B/2)`, then stretches each channel between its low and high
  percentiles. Green subjects (rocks, sponges, sand) warm up again and open water stays
  blue. The model was fitted on photos processed by Dive+, to a mean error of 2.7 %.
  - Photos are corrected at full resolution when saved.
  - Preview and video get the same correction on the GPU. It is re-measured twice a second
    and smoothed over time, so colours don't jump when the camera moves.
  - Auto white balance is locked to daylight, so the originals stay consistent.

- **Originals always kept.** Each photo is saved twice:
  - `Pictures/WeefineDiveCam/Original/IMG_DIVE_<timestamp>.jpg`: the untouched camera JPEG.
  - `Pictures/WeefineDiveCam/IMG_DIVE_<timestamp>_corr.jpg`: the corrected copy, saved only
    under water with the filter on.

  Videos go to `Movies/WeefineDiveCam/`.

- **Dive metadata in EXIF.**
  - Depth and temperature go in the user comment (`depth=35.7m temp=18.4C`).
  - Depth is also written as a negative GPS altitude, so photo tools can show and sort by it.
  - The GPS can't reach the phone underwater. Photos are therefore geo-tagged after the dive,
    interpolated by time between the last fix before descending and the first fix after
    surfacing. Videos get the latest recent fix when recording starts.

- **Shutter feedback.** On each shot the screen flashes and the frame freezes for one second.
  Saving the corrected photo takes a moment, so this confirms the shot right away.

- **Battery saving.**
  - After 1 min without a button press the screen turns black. After 5 min the camera is
    also unbound. Any button wakes the app up.
  - When the housing has been disconnected for more than 5 s (for example, switched off after
    the dive), the screen turns black with a dim "Housing connection lost" message and the
    camera stops. A video already recording keeps going.
  - The normal screen returns when the housing reconnects.
  - Black screens show only dim text that shifts every minute, to avoid OLED burn-in.

## Requirements

- Android 15 (API 35) or later, with a rear camera and Bluetooth LE.
- A Weefine WH05 Bluetooth smartphone housing.
- Permissions:
  - Camera and Bluetooth: required.
  - Microphone: optional, for video sound.
  - Location: optional, for geo-tagging.

## Building

The project uses Gradle 9.4 with the Android Gradle Plugin 9.2, Kotlin 2.4 and Jetpack Compose.
It compiles against Android API 37.1.

### With Android Studio

1. Open the project folder in a recent Android Studio. Use a version that supports AGP 9.2
   and compile SDK 37.
2. Let it sync and install any missing SDK components it asks for.
3. Connect a phone with USB debugging enabled and press **Run**.

### From the command line

You need a JDK 17 or later and the Android SDK, with `sdk.dir` set in `local.properties` or
`ANDROID_HOME` in the environment. Android Studio's bundled JDK works:

```sh
# Windows (Git Bash)
export JAVA_HOME="C:\\Program Files\\Android\\Android Studio\\jbr"
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

Then:

```sh
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew installDebug       # build and install on the connected phone
```

On Windows without Git Bash, use `gradlew.bat`.

## Known limitations

- The colour model was fitted on dives between 15 and 45 m. Shallow water, where the sensor
  still sees some red, has had little testing.
