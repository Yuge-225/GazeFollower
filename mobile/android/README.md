# GazeFollower Mobile — Android

Android implementation of the GazeFollower validation and live-test workflow.
The verified devices are a Samsung Galaxy A10 running Android 9 and a Google
Pixel 9 running Android 16. The first release is portrait-only so camera,
calibration, and screen coordinates have one unambiguous orientation.

## Verified device baseline

| Property | Galaxy A10 | Pixel 9 |
| --- | --- | --- |
| Model | Samsung SM-A105M | Google Pixel 9 (`tokay`) |
| Android API | 28 | 36 |
| Primary ABI | `armeabi-v7a` | `arm64-v8a` |
| Physical display | 720 × 1520 | 1080 × 2424 |
| Physical density | 280 dpi | 420 dpi |

The application packages native dependencies for both `armeabi-v7a` and
`arm64-v8a`. Runtime calibration uses the actual application viewport rather
than assuming the full physical display, because status/navigation insets can
reduce usable pixels.

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
the matching CPU library for each configured ABI. The project uses NDK r28c so
its native output supports 16 KB ELF alignment on current 64-bit Android
devices. Later builds use the copy cached under the ignored `.cxx` build
directory.

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

- M0: reproducible Android 9/16 build and installation on Galaxy A10 and Pixel 9. **Complete.**
- M1: front-camera preview and frame timestamps. **Complete.**
- M2: MediaPipe landmarks and face/eye crops. **Complete.**
- M3: desktop/mobile MNN inference parity. **Complete for fixed single-frame crops.**
- M4: nine-point calibration and gaze overlay. **Complete for the first on-device flow.**
- M5: validation and session export.
- M6: human-body live test and heatmap export. **In progress: live heatmap UI complete; export pending.**
