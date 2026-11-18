# GazeFollower web dashboard

Run the private dashboard from the repository root with Python 3. Set a website
password in the environment first. These commands work in zsh or bash.

```bash
printf 'Website password: '
read -rs GAZEFOLLOWER_WEB_PASSWORD
export GAZEFOLLOWER_WEB_PASSWORD
printf '\n'
python3 web/server.py
```

Open [the local dashboard](http://127.0.0.1:8765) on the computer. Sign in with
the password you entered. The server saves sessions and
stimulus videos in `web/.data` by default. Use `--data-dir` to choose a
persistent directory and `--port` to choose a different port.

After updating the code, stop the old server with **Ctrl+C**, run
`python3 web/server.py` again, and refresh the dashboard. Previously uploaded
sessions remain available when you use the same data directory.

## Test with an Android phone

Install the Android debug APK. Connect the phone over USB and run:

```bash
adb reverse tcp:8765 tcp:8765
```

Sign in on the computer and choose **Pair a phone → Generate code**. On the
phone, open **Upload Settings → Pair phone**, enter `http://127.0.0.1:8765`
and the code, then tap **Pair phone**. Each code works once and expires after
10 minutes.

Finish a test or open a previous one under **Test History** and tap **Upload
to Website**. Older tests without a participant code ask for one. Tap again to
verify the **Already Uploaded** result. Use the Sessions page to open the
test, download Excel or JSON, and replay an individual trial.

- **Manual scroll:** The replay timeline appends each round in order, shows the
  round number and time, and stops at the recorded swipe. The gaze overlays
  use only the current round's samples.
- **Autoplay:** Each video plays once and the next starts automatically during
  the test. In **Ready** mode the participant starts each video by tapping Ready.
- **Scroll (legacy):** Older records have no loops. Replay marks the unrecorded
  part after a swipe.

For Wi-Fi use, run the dashboard behind a private HTTPS reverse proxy that
forwards to loopback and sets `X-Forwarded-Proto: https`. Enter the HTTPS site
URL when pairing the phone. Forward upload bodies and Range requests.
The server limits each stimulus to 512 MiB and each session request to 32 MiB.
Use a persistent data directory and restrict access to the research team.
MP4 with H.264 video is a practical stimulus format for browser replay.

## Host a test site on Render

Use the [free web service](https://render.com/docs/free) with synthetic tests.
Render provides an HTTPS address. Sessions and stimulus files on the free
service can disappear when it restarts, redeploys, or spins down.

1. In the [Render Dashboard](https://dashboard.render.com/), choose **New → Blueprint**,
   connect the GazeFollower repository, and select the branch with this configuration.
2. Set **Blueprint Path** to `web/render.yaml`. Set
   `GAZEFOLLOWER_WEB_PASSWORD` to a new password in Render and deploy.
3. Open the assigned `https://<service-name>.onrender.com` URL on your
   computer. Sign in with the staging password, generate a pairing code, and
   pair Android with that HTTPS URL. Upload a synthetic session and verify
   replay, export, and **Already Uploaded**.

The Blueprint generates a server key to keep phone authorization valid after
restarts. For other hosts, set `GAZEFOLLOWER_DEVICE_KEY` to an independent
random secret or keep the generated `.device-key` in the persistent data
directory. Phone authorization expires after one year. Changing the website
password or server key invalidates existing pairings.

Plan a separate persistent deployment for participant data. On Render, a
[paid web service with a persistent disk](https://render.com/docs/disks)
can mount storage under `/var/data`; point `--data-dir` at that mount.
Verify the hosted upload with videos of the expected size before use.

## Exports

Click a participant code in Sessions. Check individual videos, select a whole
test, or use **Select all videos**. Download the selection as Excel or JSON.
Open a specific test to download that test alone.

Use **Edit test** to name a test and add notes. **Delete** moves a test to
**Deleted tests**, where it can be restored. Deleted tests are omitted from
exports. The Sessions page has a **Show deleted** filter if a participant's
last active test was deleted.

The Excel workbook has separate sheets for tests, trials, gaze samples,
AOI intervals, attention and validation samples. Use Test ID across sheets,
along with Trial where present. The Tests sheet stores the participant, study
version, completion time, display mode and the full session UUID. JSON retains
the nested sessions and experiment snapshots. Single-test filenames include the
participant, version, mode, UTC completion time and session UUID. Participant
filenames include the participant code and UTC export time. The session UUID
and trial IDs are stable when uploading the same test again. Older sessions
retain their scored gaze rows and aggregate excluded count. Geometry and
validation fields may be absent for those sessions.

Pixel coordinates use the app viewport origin. Normalized box coordinates use
the displayed video rectangle. Gaze coordinates relative to that rectangle can
fall outside 0–1. Relative distance is the percentage of the viewport diagonal.
Bounding box interval end times are exclusive during playback. A Scroll Mode
trial also records the swipe cutoff separately.

```bash
python3 -m unittest discover -s web -p 'test_*.py'
node --check web/public/app.js
```
