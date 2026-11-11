# GazeFollower Mobile — Android

This directory contains the Android implementation of GazeFollower. The app supports on-device gaze inference, experiment authoring, participant calibration and validation, free-viewing video tests, and per-video result replay.

For user-facing operation instructions, see [Android App Workflow](../../docs/ANDROID_APP_WORKFLOW.md).

## Current Workflow

```text
Create Version → Import Videos → Annotate Multiple AOIs → Publish
       ↓
Preview → 9-Point Calibration → 5-Point Validation
       ↓
Ready Mode or Scroll Mode
       ↓
Attention Dashboard → Per-Video Replay → Test History
```

### Authoring

- Create and manage independent experiment versions.
- Import up to five videos per version.
- Add multiple AOI bounding boxes to the same video.
- Assign reusable labels and label-specific colors.
- Pause, seek, and update boxes with timestamped keyframes.
- Publish only after every configured box has a label and visible position.

### Testing

- **Ready Mode:** the user confirms readiness before every video.
- **Scroll Mode:** the user swipes upward to leave the current video. Data after the swipe cutoff is not collected or scored for that video.
- Both modes are free viewing; AOIs and gaze predictions are hidden during stimulus presentation.

### Results

- Attention distribution by AOI label and Outside AOIs.
- Source-video, captured-gaze, valid, and excluded sample counts.
- Exact and tolerance-expanded AOI hit rates.
- Mean pixel distance to the nearest exact AOI.
- Replay with colored AOIs, gaze points, heatmap, and Scroll Mode cutoff.
- Version-specific test history using the experiment snapshot saved with each session.

## Gaze Pipeline

```text
CameraX front-camera frame
        ↓
MediaPipe face landmarks
        ↓
Face + left eye + right eye crops
        ↓
MNN `base.mnn` inference
        ↓
Ridge-regression screen calibration
        ↓
Normalized gaze position
```

The Android input behavior follows the desktop model assumptions:

- Face input: `224 × 224`.
- Eye inputs: `112 × 112`.
- Right eye is horizontally flipped.
- BGR channel order with `[0, 1]` normalization.
- Normalized face and eye rectangles are included as model inputs.

The existing model is loaded from `gazefollower/res/model_weights/base.mnn`; it is not duplicated in the Android source tree.

## Calibration and Validation

### Nine-point calibration

1. Keep the phone in portrait orientation and the full face visible.
2. Confirm that the face and both eye boxes track correctly.
3. Tap **Start 9-point calibration**.
4. Look at the center of each animated target.
5. Keep the device position and viewing distance stable.

For each point, the app ignores three warm-up predictions and collects eight samples. The resulting 72 samples fit a ridge-regression mapping from model features to normalized screen coordinates.

### Five-point validation

Validation uses five independent screen positions. During collection, only the target is shown; gaze feedback, text, and sample progress are hidden to avoid attracting the participant's gaze.

Validation passes when:

- at least 70% of samples are within a normalized radius of 12%; and
- mean normalized error is no greater than 15%.

A failed validation can be repeated, or the participant can return to calibration.

## Scoring

A gaze prediction is counted as a valid scored sample when:

```text
video timestamp >= 500 ms
AND
at least one AOI is visible at that timestamp
```

The main result metrics are:

- **Captured gaze samples:** successful predictions received while the video is being collected.
- **Valid samples:** captured predictions that meet the scoring conditions.
- **Excluded samples:** captured minus valid samples.
- **Strict hits:** valid samples inside an exact AOI.
- **Tolerant hits:** valid samples inside an AOI expanded by 20% overall.
- **Mean distance:** mean pixel distance to the nearest exact AOI boundary; samples inside an exact AOI have zero distance.

When exact AOIs overlap, a gaze point inside more than one box is assigned to the smallest matching box for the attention distribution.

## Device Baseline

| Property | Google Pixel 9 | Samsung Galaxy A10 |
| --- | --- | --- |
| Device | `tokay` | SM-A105M |
| Android | 16 | 9 |
| API | 36 | 28 |
| Primary ABI | `arm64-v8a` | `armeabi-v7a` |
| Display | 1080 × 2424 | 720 × 1520 |

Pixel 9 is the current primary development and demonstration device. The APK also retains Galaxy A10 compatibility.

The application is portrait-only. Runtime calibration uses the actual app viewport rather than assuming the full physical display size.

## Build Requirements

- JDK 17.
- Android SDK 34.
- Android min SDK 28.
- Android target SDK 34.
- CMake 3.22.1.
- NDK `28.2.13676358`.

The native build packages both `arm64-v8a` and `armeabi-v7a`. NDK r28c provides current 64-bit alignment support.

The first build downloads the pinned MNN 3.6.1 Android library and matching public headers. CMake verifies the downloaded files with pinned SHA-256 values and later builds reuse the local cache.

## Build

Open this `mobile/android` directory as the Android Studio project root, or build from the command line:

```bash
cd mobile/android
ANDROID_USER_HOME="$PWD/.android-home" ./gradlew testDebugUnitTest assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and Launch

Enable USB debugging, connect the device, and accept its RSA authorization prompt.

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gazefollower.mobile/.HomeActivity
```

Useful device checks:

```bash
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell wm density
```

After installation, the app can run directly on the phone without a computer connection.

## Session Data

Each completed video experiment creates:

```text
files/sessions/<session-id>/
├── session_summary.json
└── gaze_samples.csv
```

`session_summary.json` contains the experiment snapshot, mode, timestamps, per-video counts, AOI metrics, attention distribution, and swipe cutoffs.

`gaze_samples.csv` contains valid scored samples with video timestamps, gaze coordinates, AOI matches, hit flags, and distance values.

Experiment versions, imported videos, reusable labels, annotations, and results are stored in app-private storage. They survive USB disconnection and normal app restarts, but are removed if the app is uninstalled or its storage is cleared.

A user-facing file share/export action is not currently implemented.

## Known Limitations

- Calibration is performed for each new test and is not reused across sessions.
- AOI motion is manually annotated with keyframes; automatic person or pose tracking is not implemented.
- RGB-camera gaze estimation is sensitive to lighting, head movement, viewing distance, and camera position.
- Real-time inference does not necessarily process every front-camera frame.
- Result heatmaps and metrics are research outputs, not clinical measurements.
