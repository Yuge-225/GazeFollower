# GazeFollower: Desktop and Android Gaze Experiments

This repository extends the [original GazeFollower project](https://github.com/GanchengZhu/GazeFollower) with structured desktop experiments and an Android app for on-device, free-viewing gaze studies.

The Android app currently supports experiment authoring, multi-AOI video annotation, participant calibration and validation, Ready/Scroll video presentation, gaze replay, attention summaries, and session-level CSV/JSON results.

> GazeFollower is a research prototype. It is not a clinical system or a replacement for a dedicated infrared eye tracker.

## Contents

- [Android App](#android-app)
- [Android Workflow](#android-workflow)
- [Model and Calibration](#model-and-calibration)
- [Android Quick Start](#android-quick-start)
- [Desktop App](#desktop-app)
- [Data Outputs](#data-outputs)
- [Project Structure](#project-structure)
- [Testing](#testing)
- [Privacy](#privacy)
- [Acknowledgements and License](#acknowledgements-and-license)

## Android App

### Experiment authoring

- Create, copy, rename, archive, and delete experiment versions.
- Import up to five local stimulus videos per version.
- Add multiple AOI bounding boxes to each video.
- Assign reusable labels such as Head, Chest, Abdomen, Arm, Leg, and Foot.
- Pause playback, seek to a timestamp, and update moving AOIs with keyframes.
- Display multiple AOIs with label-specific colors.
- Publish completed versions for participant testing.

### Participant testing

- Front-camera preview with face and eye-region boxes.
- MediaPipe face landmark and eye-region localization.
- Real-time `base.mnn` gaze inference through MNN and JNI.
- Animated nine-point personal calibration.
- Five-point validation with a visual accuracy summary.
- Free-viewing stimulus presentation in Ready Mode or Scroll Mode.
- Hidden camera preview, gaze marker, and AOI truth during video testing.

### Results

- Overall attention distribution by AOI label.
- Per-video captured, valid, and excluded sample counts.
- Exact and tolerance-expanded AOI hit rates.
- Mean distance to the nearest exact AOI.
- Synchronized video replay with AOI boxes, gaze points, and heatmap.
- Scroll cutoff handling: gaze data after the accepted swipe is not scored.
- Version-specific test history and historical replay.

## Android Workflow

```text
Manage Versions
    ↓
Create Version → Add Videos → Annotate AOIs → Publish
    ↓
Start a Test
    ↓
Preview → 9-Point Calibration → 5-Point Validation
    ↓
Ready Mode or Scroll Mode
    ↓
Result Dashboard → Per-Video Replay → Test History
```

The experiment is **free viewing**: participants watch the videos naturally and are not instructed to look at a particular body region. AOI annotations are hidden during testing and used only for later analysis.

See [Android App Workflow](docs/ANDROID_APP_WORKFLOW.md) for the step-by-step user guide.

## Model and Calibration

The desktop and Android implementations share the same main model assumptions:

- Face input: `224 × 224`.
- Left and right eye inputs: `112 × 112`.
- Right-eye crop is horizontally flipped.
- Input images use BGR channel order and `[0, 1]` normalization.
- Face and eye rectangles are included as normalized model inputs.
- Android uses `gazefollower/res/model_weights/base.mnn`.

On Android, CameraX supplies front-camera frames. MediaPipe locates the face and eye regions, MNN produces gaze features, and a participant-specific ridge-regression calibration maps those features to normalized screen coordinates.

Calibration uses nine screen positions. Validation uses five independent positions and must pass before the video experiment begins.

## Android Quick Start

### Requirements

- Android Studio or Android SDK command-line tools.
- JDK 17.
- Android SDK 34.
- CMake 3.22.1.
- Android NDK r28c (`28.2.13676358`).
- An Android device with a front camera and USB debugging enabled.

The APK packages native libraries for both `armeabi-v7a` and `arm64-v8a`.

### Verified devices

| Device | Android | ABI | Display |
| --- | --- | --- | --- |
| Google Pixel 9 (`tokay`) | Android 16 | `arm64-v8a` | 1080 × 2424 |
| Samsung Galaxy A10 (SM-A105M) | Android 9 | `armeabi-v7a` | 720 × 1520 |

Pixel 9 is the current primary development and demonstration device. Galaxy A10 compatibility is retained.

### Build

```bash
cd mobile/android
ANDROID_USER_HOME="$PWD/.android-home" ./gradlew assembleDebug
```

The first native build downloads the pinned MNN Android library and headers. Later builds reuse the local Gradle and CMake caches.

### Install and launch

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gazefollower.mobile/.HomeActivity
```

The installed app can be opened directly from the phone without reconnecting it to a computer. Imported videos, experiment versions, and saved results remain available until the app is uninstalled or its storage is cleared.

More build and implementation details are available in [mobile/android/README.md](mobile/android/README.md).

## Desktop App

The desktop workflow remains available for camera preview, personal calibration, validation, and the human-body Live Test.

```text
Session Setup → Camera Preview → Personal Calibration
                                   ├─ Validation → Accuracy Results
                                   └─ Live Test  → Body Heatmap Results
```

### Desktop quick start

The tested macOS setup uses Python 3.10 on Apple Silicon.

```bash
python3.10 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-macos-arm64.txt
python example/gui_launcher.py
```

The launcher can also be opened on macOS with `run_gui.command`.

## Data Outputs

### Android video experiments

Each completed Android test creates an app-private session directory:

```text
files/sessions/<session-id>/
├── session_summary.json
└── gaze_samples.csv
```

- `session_summary.json` stores the experiment snapshot, presentation mode, per-video sample counts, AOI metrics, attention distribution, and Scroll Mode cutoffs.
- `gaze_samples.csv` stores the valid scored gaze samples with video timestamps, screen/video coordinates, AOI hits, labels, and distance values.

The files are generated and used by the app. A user-facing share/export action is not currently provided.

### Desktop experiments

Desktop sessions are stored under:

```text
results/<participant-id>/<session-id>/
```

Depending on the selected desktop workflow, the directory can contain gaze samples, validation measurements, summary JSON, calibration data, and heatmap images.

## Project Structure

```text
GazeFollower/
├── gazefollower/                  # Original Python package and shared model assets
├── example/                       # Desktop launcher and experiment workflows
├── mobile/android/                # Android application
│   └── app/src/main/
│       ├── java/                  # UI, camera, calibration, experiments, and results
│       └── cpp/                   # MNN JNI bridge
├── docs/
│   ├── ANDROID_APP_WORKFLOW.md    # Android user workflow
│   └── images/                    # Documentation-only synthetic graphics
└── tests/                         # Desktop tests
```

## Testing

### Android unit tests and APK

```bash
cd mobile/android
ANDROID_USER_HOME="$PWD/.android-home" ./gradlew testDebugUnitTest assembleDebug
```

### Android device tests

```bash
cd mobile/android
ANDROID_USER_HOME="$PWD/.android-home" ./gradlew connectedDebugAndroidTest
```

`connectedDebugAndroidTest` may remove the application after testing. Reinstall the debug APK before manual testing if necessary.

### Desktop tests

```bash
pytest tests
```

## Privacy

- Camera frames are processed locally by the current Android prototype.
- Imported videos, annotations, and test results are stored in app-private storage.
- Documentation must not contain participant faces or identifiable test-session screenshots.
- The synthetic workflow graphic contains no participant image or recorded gaze data.
- Researchers are responsible for consent, retention, access control, and institutional requirements.

## Acknowledgements and License

This project is derived from [GanchengZhu/GazeFollower](https://github.com/GanchengZhu/GazeFollower). The upstream project provides the gaze-tracking architecture, model integration, calibration framework, and Python API on which this work is based.

Please cite the upstream project when using this derivative work:

```bibtex
@article{zhu2025gazefollower,
  title={GazeFollower: An open-source system for deep learning-based gaze tracking with web cameras},
  author={Zhu, Gancheng and Duan, Xiaoting and Huang, Zehao and Wang, Rong and Zhang, Shuai and Wang, Zhiguo},
  journal={Proceedings of the ACM on Computer Graphics and Interactive Techniques},
  volume={8},
  number={2},
  pages={1--18},
  year={2025},
  publisher={ACM New York, NY}
}
```

This repository retains the upstream **CC BY-NC-SA 4.0** license. See [LICENSE-CC-BY-NC-SA](LICENSE-CC-BY-NC-SA).
