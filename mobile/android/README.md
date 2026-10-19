# GazeFollower Mobile — Android

Android implementation of the GazeFollower validation and live-test workflow.
The first target device is a Samsung Galaxy A10 (SM-A105M) running Android 9
(API 28). The first release is portrait-only so camera, calibration, and screen
coordinates have one unambiguous orientation.

## Verified device baseline

| Property | Value |
| --- | --- |
| Model | Samsung SM-A105M (Galaxy A10) |
| Android API | 28 |
| Primary ABI | `armeabi-v7a` |
| ABI list | `armeabi-v7a,armeabi` |
| Physical display | 720 × 1520 |
| Physical density | 280 dpi |

The application packages native dependencies only for `armeabi-v7a`. Runtime
calibration uses the actual application viewport rather than assuming the full
physical display, because status/navigation insets can reduce usable pixels.

## Scope

1. Front-camera preview.
2. MediaPipe face and eye-region localization.
3. `base.mnn` gaze inference.
4. Nine-point calibration with an animated target and calibrated gaze mapping.
5. Validation with CSV/JSON export.
6. Human-body live test with a real-time gaze heatmap and result screen.

## Open in Android Studio

Open this `mobile/android` directory as the project root. The build maps the
existing files in `gazefollower/res/model_weights` and `gazefollower/res/image`
into the Android asset set, avoiding duplicate model weights in Git.

The first native build downloads the official MNN 3.6.1 Android release and
its matching public headers from Alibaba's GitHub release. CMake verifies the
downloaded archive and every header with pinned SHA-256 values, then packages
only the `armeabi-v7a` CPU library required by the Galaxy A10. Later builds use
the copy cached under the ignored `.cxx` build directory.

## Nine-point calibration

1. Hold the phone in the same portrait position that will be used for testing.
2. Keep the complete face visible until all three face/eye rectangles track.
3. Tap **Start 9-point calibration**. The camera preview and face rectangles
   fade out, while camera analysis continues in the background.
4. Look at the red center of each animated target without following it with
   the phone. The outer ring shows collection progress.
5. Each point waits for three valid inference frames, then collects eight.
6. After point 9, choose **Start body Live Test** or **Recalibrate**. Live Test
   places the calibrated marker over the body diagram and accumulates a
   full-screen heatmap. A panel reports the body region under the current gaze,
   screen coordinates, and sample counts. Tap **Finish** to freeze the result,
   **Clear** to reset the current heatmap, or **Preview** on the result screen
   to return to the camera.

Calibration currently lives for the running app session. Persistence and
export are intentionally deferred to the validation/export milestone.

## Device check

Enable USB debugging, connect the phone, accept its RSA authorization prompt,
then run:

```bash
adb devices -l
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell wm density
```

The ABI result determines whether the first native MNN build targets
`arm64-v8a`, `armeabi-v7a`, or both.

## Milestones

- M0: reproducible Android 9 build and installation. **Complete.**
- M1: front-camera preview and frame timestamps. **Complete.**
- M2: MediaPipe landmarks and face/eye crops. **Complete.**
- M3: desktop/mobile MNN inference parity. **Complete for fixed single-frame crops.**
- M4: nine-point calibration and gaze overlay. **Complete for the first on-device flow.**
- M5: validation and session export.
- M6: human-body live test and heatmap export. **In progress: live heatmap UI complete; export pending.**
