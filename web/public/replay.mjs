export const playbackModes = {
  MANUAL_SCROLL: {name: "Manual scroll", hint: "Each video repeats until the participant swipes to the next one."},
  AUTOPLAY: {name: "Autoplay", hint: "The next video starts automatically when the current one ends."},
  READY: {name: "Ready", hint: "The participant taps Ready to start each video."},
  SCROLL: {name: "Scroll (legacy)", hint: "The participant could swipe to skip the rest of a video. Videos did not repeat."},
};

export function modeOf(summary) {
  return [summary.playbackMode, summary.presentationMode]
    .find(mode => Object.hasOwn(playbackModes, mode)) ?? "UNKNOWN";
}

export function replayTimeline(trial, mode) {
  const manual = mode === "MANUAL_SCROLL";
  const viewed = Math.max(0, trial.presentedDurationMs ?? trial.videoDurationMs ?? 0);
  const final = Math.max(0, trial.finalVideoTimeMs ?? viewed);
  const loops = manual ? Math.max(0, Math.trunc(trial.completedLoops ?? 0)) : 0;
  const recordedLoop = loops > 0 ? Math.trunc((viewed - final) / loops) : 0;
  const full = Math.max(1, recordedLoop > 0 ? recordedLoop : trial.videoDurationMs || final);
  const last = Math.min(final, full);
  const oldSwipe = !manual && trial.endedBySwipe;
  const passCount = loops + (last > 0 || loops === 0 ? 1 : 0);
  const duration = oldSwipe ? full : loops * full + last;
  function positionAt(ms) {
    const elapsed = Math.max(0, Math.min(ms, duration));
    const pass = Math.min(passCount, Math.floor(elapsed / full) + 1);
    const start = (pass - 1) * full;
    const end = Math.min(start + full, duration);
    return {pass, start, end, local: elapsed - start, elapsed, afterSwipe: oldSwipe && elapsed > last};
  }
  return {manual, passCount, duration, cutoff: oldSwipe ? last : null, positionAt};
}

export function replaySamples(samples, trialId) {
  const passes = new Map();
  for (const sample of samples) {
    if (sample.trialId !== trialId || !sample.scored) continue;
    const pass = sample.playbackPass ?? 1;
    if (!passes.has(pass)) passes.set(pass, []);
    passes.get(pass).push(sample);
  }
  for (const points of passes.values()) points.sort((a, b) => a.videoTimeMs - b.videoTimeMs);
  return passes;
}

export function replayClock(ms) {
  const tenths = Math.floor(ms / 100);
  return Math.floor(tenths / 600) + ":" + String(Math.floor(tenths / 10) % 60).padStart(2, "0") + "." + tenths % 10;
}
