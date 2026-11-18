import assert from "node:assert/strict";
import test from "node:test";
import {modeOf, replayClock, replaySamples, replayTimeline} from "./public/replay.mjs";

test("manual replay appends two complete rounds and a partial round", () => {
  const replay = replayTimeline({videoDurationMs: 5050, presentedDurationMs: 12400,
    completedLoops: 2, finalVideoTimeMs: 2400, endedBySwipe: true}, "MANUAL_SCROLL");
  assert.equal(replay.duration, 12400);
  assert.equal(replay.passCount, 3);
  assert.deepEqual(replay.positionAt(5000), {pass: 2, start: 5000, end: 10000, local: 0,
    elapsed: 5000, afterSwipe: false});
  const end = replay.positionAt(20000);
  assert.equal(end.pass, 3);
  assert.equal(end.local, 2400);
  assert.equal(end.afterSwipe, false);
  assert.equal(replayClock(67300), "1:07.3");
});

test("swiping at the start of a new round does not create an empty round", () => {
  const replay = replayTimeline({videoDurationMs: 5000, presentedDurationMs: 10000,
    completedLoops: 2, finalVideoTimeMs: 0, endedBySwipe: true}, "MANUAL_SCROLL");
  assert.equal(replay.passCount, 2);
  assert.equal(replay.positionAt(10000).local, 5000);
});

test("overlays contain only the selected round's scored points", () => {
  const points = replaySamples([
    {trialId: "a", scored: true, videoTimeMs: 2000},
    {trialId: "a", scored: true, playbackPass: 2, videoTimeMs: 1200},
    {trialId: "a", scored: false, playbackPass: 2, videoTimeMs: 100},
    {trialId: "b", scored: true, playbackPass: 2, videoTimeMs: 900},
    {trialId: "a", scored: true, playbackPass: 2, videoTimeMs: 800},
  ], "a");
  assert.deepEqual(points.get(1).map(p => p.videoTimeMs), [2000]);
  assert.deepEqual(points.get(2).map(p => p.videoTimeMs), [800, 1200]);
});

test("mode fallback preserves Ready and legacy Scroll and never infers Autoplay", () => {
  assert.equal(modeOf({presentationMode: "READY", playbackMode: "AUTOPLAY"}), "AUTOPLAY");
  assert.equal(modeOf({presentationMode: "READY"}), "READY");
  assert.equal(modeOf({presentationMode: "SCROLL"}), "SCROLL");
  const trial = {videoDurationMs: 5000, presentedDurationMs: 2100, endedBySwipe: true};
  const replay = replayTimeline(trial, "SCROLL");
  assert.equal(replay.duration, 5000);
  assert.equal(replay.positionAt(2100).afterSwipe, false);
  assert.equal(replay.positionAt(2101).afterSwipe, true);
  assert.equal(replayTimeline({...trial, endedBySwipe: false}, "AUTOPLAY").duration, 2100);
});
