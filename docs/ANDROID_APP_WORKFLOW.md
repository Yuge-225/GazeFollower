# GazeFollower Android App Workflow

GazeFollower is an Android app for creating and running free-viewing gaze experiments. A researcher prepares stimulus videos and marks Areas of Interest (AOIs). A participant then watches the videos naturally while the app estimates their gaze.

## Complete Workflow

```text
Create Version
    ↓
Add Videos
    ↓
Add and Label AOI Boxes
    ↓
Publish Version
    ↓
Preview → Calibration → Validation
    ↓
Watch Videos
    ↓
View Results and Replay
```

The home screen has two options:

- **Manage Versions** — prepare videos and AOI annotations.
- **Start a Test** — run a published experiment with a participant.

## 1. Manage Versions

### Create a Version

Open:

```text
Manage Versions → + New Version
```

Enter a short name for the experiment. Each version keeps its own videos, AOI annotations, and test results.

If a version already has results, create a copy before changing its setup. This keeps earlier results connected to the original setup.

### Add Videos

Open the version and select:

```text
Edit Setup → + Add video
```

Choose up to five stimulus videos from the phone. The setup page shows whether each video has been annotated.

### Add AOI Bounding Boxes

Tap a video to open the **Box Editor**.

An AOI bounding box marks a meaningful region, such as Head, Chest, Abdomen, Arm, Leg, or Foot. One video can contain several AOIs.

To annotate a video:

1. Tap **+ Box** to add a bounding box.
2. Move and resize the box over the correct region.
3. Tap **Label** and choose its body-part label.
4. Play the video.
5. When the box no longer follows the region, tap **Pause & edit boxes**.
6. Adjust the box and resume playback.
7. Repeat when another adjustment is needed.

Use the progress bar to move to a specific moment. The app shows a loading indicator while retrieving that video frame.

Each label has a different color, so multiple boxes can be distinguished during editing and replay.

### Publish the Version

Return to the setup page and tap **Publish Version**.

A version can be published when:

- it contains at least one video;
- every box has a saved position;
- every box has a label.

Only published versions can be used for testing.

## 2. Start a Test

Open:

```text
Start a Test → Select Version → Start a New Test
```

Choose one of the two presentation modes:

| Mode | Operation |
| --- | --- |
| **Ready Mode** | Tap **Ready** before each video. Each video normally plays to the end. |
| **Scroll Mode** | Swipe up whenever you want to leave the current video and continue to the next one. |

Both modes are free viewing. The participant watches naturally and is not told which body part to look at.

### Step 1 — Preview

The preview shows the front camera and the detected face and eye boxes.

Before continuing:

- keep the full face visible;
- confirm that all three boxes follow the face and eyes;
- hold the phone in portrait orientation;
- keep a comfortable and stable viewing distance.

Tap **Start 9-point calibration**.

### Step 2 — Calibration

Nine targets appear at different screen positions.

Look steadily at the center of each target. The app automatically moves to the next point after collecting enough gaze samples.

The purpose of calibration is to adapt the gaze model to the current participant and phone position.

### Step 3 — Validation

Five new targets appear after calibration.

Look at each target in the same way. No gaze marker, instructions, or sample counter are displayed during collection because they could distract the participant.

The result screen shows how closely the predicted gaze matched the validation targets.

- If validation passes, tap **Start video experiment**.
- If validation fails, repeat validation or recalibrate.

### Step 4 — Video Test

The participant watches the videos naturally.

During the test:

- the front-camera preview is hidden;
- AOI boxes are hidden;
- the predicted gaze point is hidden;
- gaze estimation continues in the background.

In **Ready Mode**, tap **Ready** before every video.

In **Scroll Mode**, swipe up to move to the next video. Gaze data after the swipe time is not included for the previous video.

## 3. View Results

After the final video, the app opens the result dashboard.

### Attention

The Attention section shows the percentage of valid gaze samples assigned to each labeled AOI. Samples outside all AOIs are shown as **Outside AOIs**.

### Video Results

Tap a video to open its detail page. The replay shows:

- the original video;
- colored AOI boxes;
- gaze locations and heatmap;
- attention distribution;
- additional technical metrics.

For Scroll Mode, the replay marks the section after the swipe as **Gaze Data Not Available**.

### Main Metrics

| Metric | Meaning |
| --- | --- |
| **Gaze Samples Captured** | Successful gaze predictions received while the video was playing. |
| **Valid Samples** | Captured predictions that were eligible for scoring. |
| **Inside Exact AOIs** | Valid gaze samples inside the original bounding boxes. |
| **Inside Tolerant AOIs** | Valid gaze samples inside slightly expanded bounding boxes. |
| **Mean Distance** | Average pixel distance from gaze to the nearest original AOI box. |

## 4. View Previous Tests

Open:

```text
Manage Versions → Select Version → Test History
```

Select a test to view its overall result. Select a video to view its replay and detailed metrics.

## Quick Checklist

### Before a Test

1. Confirm that the correct version is published.
2. Check every video and AOI annotation.
3. Select Ready Mode or Scroll Mode.
4. Confirm face and eye tracking in Preview.
5. Complete calibration and validation.

### After a Test

1. Check the total valid-sample count.
2. Review the Attention distribution.
3. Open each video replay.
4. Check whether gaze and AOI boxes align correctly.
