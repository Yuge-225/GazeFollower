const main = document.querySelector("#main");
const number = new Intl.NumberFormat();
const formatNumber = value => value == null ? "Unavailable" : number.format(value);
const percent = value => value == null ? "Unavailable" : (100 * value).toFixed(1) + "%";
const seconds = value => value == null ? "Unavailable" : (value / 1000).toFixed(2) + " s";
let cleanup = () => {};
let routeVersion = 0;
let sessionCache;

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value);
  }
  node.append(...children);
  return node;
}

async function get(url) {
  const response = await fetch(url);
  if (response.status === 401) throw new Error("Your sign-in expired. Reload the page to sign in again.");
  if (!response.ok) throw new Error("Could not load this page. Refresh to try again.");
  return response.json();
}

function table(headings, rows) {
  const body = rows.map(row => el("tr", {}, ...row.map(value => el("td", {}, value ?? "—"))));
  return el("div", {class: "table-wrap"}, el("table", {},
    el("thead", {}, el("tr", {}, ...headings.map(value => el("th", {scope: "col"}, value)))),
    el("tbody", {}, ...body)));
}

function metrics(title, entries) {
  return el("section", {class: "panel"}, el("h2", {}, title),
    el("dl", {}, ...entries.flatMap(([label, value]) => [el("dt", {}, label), el("dd", {}, value)])));
}

function downloadLinks(base, allTests = false) {
  return el("div", {class: "actions"},
    el("a", {href: base + "/export.json", class: "button secondary"}, allTests ? "All tests · JSON" : "This test · JSON"),
    el("a", {href: base + "/export.xlsx", class: "button"}, allTests ? "All tests · Excel" : "This test · Excel"));
}

function sessionTitle(session) {
  return session.research?.name || session.participantCode + " / V" + session.experimentVersion + " / " + session.sessionId.slice(0, 8);
}
const modeName = mode => mode === "SCROLL" ? "Scroll" : "Ready";
const participantUrl = code => "#participant/" + encodeURIComponent(code);

function sessionRow(session, allSessions) {
  const id = session.sessionId.replaceAll("-", "");
  const peerIds = allSessions.filter(peer => peer.participantCode === session.participantCode
    && peer.sessionId !== session.sessionId).map(peer => peer.sessionId.replaceAll("-", ""));
  let length = 8;
  while (peerIds.some(peer => peer.startsWith(id.slice(0, length)))) length += 1;
  return [el("a", {href: "#session/" + session.sessionId, title: session.sessionId}, id.slice(0, length)),
    "V" + session.experimentVersion + " · " + session.experimentName,
    modeName(session.presentationMode), new Date(session.completedAtMs).toLocaleString(),
    session.trialCount, session.validSamples];
}

const sessionColumns = ["Test ID", "Study", "Mode", "Completed", "Trials", "Valid samples"];

function participantDetail(data, code, selections = new Set()) {
  const tests = data.filter(session => session.participantCode === code);
  const back = el("a", {href: "#sessions", class: "back"}, "← Sessions");
  if (!tests.length) {
    main.replaceChildren(back, el("h1", {}, code), el("p", {}, "No uploaded tests for this participant."));
    return;
  }
  if (tests.some(test => !Array.isArray(test.trials))) {
    main.replaceChildren(back, el("h1", {}, code),
      el("p", {class: "panel", role: "status"},
        "The dashboard server is still running an older version. Restart it, then refresh this page to select videos or edit tests."),
      table(sessionColumns, tests.map(test => sessionRow(test, tests))));
    return;
  }
  const active = tests.filter(test => !test.research?.deleted);
  const deleted = tests.filter(test => test.research?.deleted);
  const status = el("div", {role: "status", class: "muted"});
  const selectAll = el("input", {type: "checkbox"});
  const excel = el("a", {class: "button", hidden: ""}, "Download Excel");
  const json = el("a", {class: "button secondary", hidden: ""}, "Download JSON");
  const entries = [];
  const resultPath = "/api/participants/" + encodeURIComponent(code) + "/export.";
  let confirmation;

  function syncSelection() {
    const total = entries.reduce((count, entry) => count + entry.videos.length, 0);
    selectAll.checked = total > 0 && selections.size === total;
    selectAll.indeterminate = selections.size > 0 && selections.size < total;
    selectAll.disabled = total === 0;
    for (const {toggle, videos} of entries) {
      const checked = videos.filter(item => selections.has(item.key)).length;
      toggle.checked = videos.length > 0 && checked === videos.length;
      toggle.indeterminate = checked > 0 && checked < videos.length;
      for (const item of videos) item.checkbox.checked = selections.has(item.key);
    }
    status.textContent = selections.size + " of " + total + " videos selected";
    // An explicit selection remains fixed if another test is uploaded before download.
    const search = new URLSearchParams();
    for (const key of selections) search.append("trial", key);
    for (const [link, extension] of [[excel, "xlsx"], [json, "json"]]) {
      link.hidden = selections.size === 0;
      if (selections.size) link.href = resultPath + extension + "?" + search.toString();
      else link.removeAttribute("href");
    }
  }

  async function manage(test, action, fields = {}) {
    const response = await fetch("/api/sessions/" + test.sessionId + "/manage", {
      method: "POST", credentials: "same-origin",
      headers: {"Content-Type": "application/json"}, body: JSON.stringify({action, ...fields}),
    });
    if (response.status === 401) throw new Error("Your sign-in expired. Reload the page to sign in again.");
    const result = await response.json();
    if (!response.ok) throw Error(result.error || "Could not update test.");
    test.research = result;
  }

  function errorText(error) { return error.message || "Could not update test."; }

  function testCard(test) {
    const display = el("a", {href: "#session/" + test.sessionId}, sessionTitle(test));
    const note = el("p", {class: "test-note"}, test.research?.notes || "");
    const updateStatus = el("p", {role: "status", class: "muted"});
    const toggle = el("input", {type: "checkbox", "aria-label": "Select all videos for " + sessionTitle(test)});
    const expand = el("details", {}, el("summary", {}, (test.trials?.length || 0) + " videos"));
    const editor = el("details", {class: "test-editor"}, el("summary", {}, "Edit test"));
    const name = el("input", {name: "name", maxlength: "120"});
    const notes = el("textarea", {name: "notes", maxlength: "2000", rows: "3"});
    name.value = test.research?.name || "";
    notes.value = test.research?.notes || "";
    const save = el("button", {type: "submit"}, "Save");
    editor.append(el("form", {onsubmit: async event => {
      event.preventDefault();
      save.disabled = true;
      try {
        await manage(test, "edit", {name: name.value, notes: notes.value});
        display.textContent = sessionTitle(test);
        toggle.setAttribute("aria-label", "Select all videos for " + sessionTitle(test));
        note.textContent = test.research.notes;
        editor.open = false;
        updateStatus.textContent = "Test updated.";
        sessionCache = undefined;
      } catch (error) { updateStatus.textContent = errorText(error); }
      finally { save.disabled = false; }
    }}, el("label", {}, "Test name", name), el("label", {}, "Notes", notes), save));
    const remove = el("button", {type: "button", class: "secondary danger", onclick: () => {
      if (confirmation?.isConnected) confirmation.remove();
      const confirm = el("button", {type: "button", class: "danger-fill"}, "Delete test");
      confirmation = el("div", {class: "delete-confirm"},
        el("span", {}, "Move this test to Deleted tests?"),
        el("button", {type: "button", class: "secondary", onclick: () => confirmation.remove()}, "Cancel"), confirm);
      confirm.addEventListener("click", async () => {
        confirm.disabled = true;
        try {
          await manage(test, "delete");
          sessionCache = undefined;
          for (const video of videos) selections.delete(video.key);
          participantDetail(data, code, selections);
        } catch (error) { updateStatus.textContent = errorText(error); confirm.disabled = false; }
      });
      card.append(confirmation);
      confirm.focus();
    }}, "Delete");

    const videos = (test.trials || []).map(trial => {
      const key = test.sessionId + ":" + trial.index;
      const checkbox = el("input", {type: "checkbox", onchange: () => {
        if (checkbox.checked) selections.add(key); else selections.delete(key);
        syncSelection();
      }});
      expand.append(el("label", {class: "video-choice"}, checkbox, trial.name));
      return {key, checkbox};
    });
    toggle.disabled = videos.length === 0;
    toggle.addEventListener("change", () => {
      for (const video of videos) {
        if (toggle.checked) selections.add(video.key); else selections.delete(video.key);
      }
      syncSelection();
    });
    entries.push({videos, toggle});
    const card = el("article", {class: "panel test-card"},
      el("div", {class: "test-card-header"},
        el("div", {class: "test-card-name"}, toggle, display),
        el("div", {class: "test-card-tools"}, editor, remove)),
      el("div", {class: "muted"}, "V" + test.experimentVersion + " · " + modeName(test.presentationMode)
        + " · " + new Date(test.completedAtMs).toLocaleString()),
      note, expand, updateStatus);
    return card;
  }

  const cards = active.map(testCard);
  selectAll.addEventListener("change", () => {
    selections.clear();
    if (selectAll.checked) for (const entry of entries) for (const item of entry.videos) selections.add(item.key);
    syncSelection();
  });
  const trash = el("details", {class: "panel deleted-tests"},
    el("summary", {}, "Deleted tests (" + deleted.length + ")"));
  for (const test of deleted) {
    const restore = el("button", {type: "button", class: "secondary"}, "Restore");
    const message = el("span", {role: "status", class: "muted"});
    restore.addEventListener("click", async () => {
      restore.disabled = true;
      try {
        await manage(test, "restore");
        sessionCache = undefined;
        participantDetail(data, code, selections);
      } catch (error) { message.textContent = errorText(error); restore.disabled = false; }
    });
    trash.append(el("div", {class: "deleted-row"}, el("span", {}, sessionTitle(test)), restore, message));
  }
  main.replaceChildren(back, el("h1", {}, code),
    el("div", {class: "toolbar selection-toolbar"}, el("label", {class: "select-all"}, selectAll, "Select all videos"),
      status, el("div", {class: "actions"}, json, excel)),
    ...(cards.length ? cards : [el("p", {class: "panel empty"}, "No active tests for this participant.")]),
    ...(deleted.length ? [trash] : []));
  syncSelection();
}

function pairingPanel() {
  const output = el("p", {role: "status"});
  const generate = el("button", {type: "button", class: "secondary"}, "Generate code");
  generate.addEventListener("click", async () => {
    generate.disabled = true;
    output.textContent = "Generating a code…";
    try {
      const response = await fetch("/api/pairing/codes", {method: "POST"});
      if (response.status === 401) throw new Error("Your sign-in expired. Reload the page and sign in again.");
      if (!response.ok) throw new Error("Could not generate a pairing code. Try again.");
      const {code, expiresInSeconds} = await response.json();
      output.replaceChildren(el("strong", {}, code), " · Expires in " + expiresInSeconds / 60 + " minutes");
    } catch (error) {
      output.textContent = error.message;
    } finally {
      generate.disabled = false;
    }
  });
  return el("details", {class: "panel"}, el("summary", {}, "Pair a phone"),
    el("p", {}, "Open Upload Settings on the phone and enter the code. Each code works once."), generate, output);
}

function sessions(data) {
  const participant = el("input", {type: "search", placeholder: "e.g. P014"});
  const version = el("select", {}, el("option", {value: ""}, "All versions"));
  const mode = el("select", {}, el("option", {value: ""}, "Both modes"),
    el("option", {value: "READY"}, "Ready"), el("option", {value: "SCROLL"}, "Scroll"));
  const date = el("input", {type: "date"});
  const showDeleted = el("input", {type: "checkbox"});
  const output = el("section", {"aria-live": "polite"});
  const versions = new Map(data.map(s => [s.experimentId + "/" + s.experimentVersion,
    "V" + s.experimentVersion + " · " + s.experimentName]));
  for (const [value, label] of versions) version.append(el("option", {value}, label));
  function render() {
    const rows = data.filter(s => (!s.research?.deleted || showDeleted.checked)
        && s.participantCode.toLowerCase().includes(participant.value.trim().toLowerCase())
        && (!version.value || version.value === s.experimentId + "/" + s.experimentVersion)
        && (!mode.value || mode.value === s.presentationMode)
        && (!date.value || new Date(s.completedAtMs).toLocaleDateString("sv-SE") === date.value))
      .map(s => [el("a", {href: participantUrl(s.participantCode)}, s.participantCode),
        ...(s.research?.deleted ? ["Deleted", ...sessionRow(s, data).slice(1)] : sessionRow(s, data))]);
    output.replaceChildren(rows.length
      ? table(["Participant", ...sessionColumns], rows)
      : el("p", {class: "panel empty"}, data.length ? "No sessions match these filters." :
        "No sessions yet. Open a test result on your phone and tap Upload to Website."));
  }
  for (const field of [participant, version, mode, date, showDeleted]) field.addEventListener("input", render);
  render();
  main.replaceChildren(el("h1", {}, "Experiment sessions"),
    el("div", {class: "toolbar"}, el("label", {}, "Participant code", participant),
      el("label", {}, "Study version", version), el("label", {}, "Mode", mode),
      el("label", {}, "Completed on", date),
      ...(data.some(test => test.research?.deleted) ? [el("label", {}, "Show deleted", showDeleted)] : []),
      el("button", {class: "secondary", onclick: navigate}, "Refresh")),
    output, pairingPanel());
}

function testDetail(bundle) {
  const {summary} = bundle;
  const attention = new Map();
  for (const trial of summary.trials) {
    for (const label of trial.attentionByLabel ?? []) {
      const prior = attention.get(label.labelId) ?? {name: label.labelName, count: 0};
      attention.set(label.labelId, {...prior, count: prior.count + label.samples});
    }
  }
  const valid = summary.trials.reduce((total, trial) => total + trial.validSamples, 0);
  const validation = summary.validation;
  const validationRows = validation ? [["Result", validation.passed ? "Passed" : "Needs attention"],
    ["Within tolerance", percent(validation.hitRate)], ["Mean normalized error", percent(validation.meanNormalizedError)],
    ["Samples", formatNumber(validation.sampleCount)]] : [["Result", "Unavailable"]];
  const attentionRows = [...attention.values()].sort((a, b) => b.count - a.count)
    .map(item => [item.name, percent(item.count / valid) + " · " + item.count]);
  const rows = summary.trials.map(trial => [
    el("a", {href: "#session/" + summary.sessionId + "/" + trial.trialIndex}, trial.trialIndex + ". " + trial.videoName),
    seconds(trial.presentedDurationMs), formatNumber(trial.validSamples), percent(trial.strictHitRate),
    percent(trial.tolerantHitRate), trial.playbackFailed ? "Playback error" : trial.endedBySwipe ? "Swiped" : "Completed",
  ]);
  main.replaceChildren(el("a", {href: participantUrl(summary.participantCode), class: "back"}, "← " + summary.participantCode + " tests"),
    el("div", {class: "page-heading"}, el("div", {}, el("h1", {}, sessionTitle(summary)),
      el("div", {class: "muted"}, summary.experimentName + " · " + modeName(summary.presentationMode) +
        " · " + new Date(summary.completedAtMs).toLocaleString())), downloadLinks("/api/sessions/" + summary.sessionId)),
    ...(summary.research?.notes ? [el("p", {class: "test-note"}, summary.research.notes)] : []),
    ...(bundle.sampleCoverage === "scored_only" ? [el("p", {class: "muted"},
      "This older session contains scored gaze rows and a count of excluded samples.")] : []),
    el("div", {class: "stats"}, metrics("Validation", validationRows),
      metrics("Attention · " + formatNumber(valid) + " valid samples", attentionRows.length ? attentionRows : [["Distribution", "Unavailable"]])),
    el("h2", {}, "Trials"),
    table(["Video", "Viewed", "Valid samples", "Strict hits", "Tolerance hits", "End"], rows));
}

function trialDetail(bundle, trial) {
  const {summary} = bundle;
  const video = el("video", {src: "/media/" + trial.assetHash, preload: "metadata", playsinline: ""});
  const canvas = el("canvas", {"aria-hidden": "true"});
  const notice = el("div", {class: "cutoff", hidden: ""}, "Gaze data unavailable after swipe");
  const stage = el("div", {class: "replay-stage"}, video, canvas, notice);
  const seek = el("input", {type: "range", min: "0", max: trial.videoDurationMs, value: "0", "aria-label": "Replay time"});
  const timeline = el("div", {class: "timeline"}, seek);
  if (trial.endedBySwipe && trial.videoDurationMs > 0) {
    const marker = el("span", {class: "swipe-marker", title: "Swiped at " + seconds(trial.presentedDurationMs)});
    marker.style.left = Math.min(100, 100 * trial.presentedDurationMs / trial.videoDurationMs) + "%";
    timeline.append(marker);
  }
  const time = el("span", {});
  const play = el("button", {onclick: () => {
    if (video.paused) video.play().catch(() => { time.textContent = "Video playback unavailable"; });
    else video.pause();
  }}, "Play");
  const controls = el("div", {class: "toggles"});
  const layers = {};
  for (const [key, label, selected] of [["heat", "Heatmap", true], ["point", "Gaze point", true],
    ["exact", "Exact boxes", true], ["tolerant", "Tolerance boxes", false]]) {
    const input = el("input", {type: "checkbox"});
    input.checked = selected;
    layers[key] = input;
    input.addEventListener("change", () => draw());
    controls.append(el("label", {}, input, label));
  }
  const geometry = trial.geometry;
  const viewport = geometry?.videoRectPx ? geometry : null;
  const samples = bundle.samples.filter(sample => sample.trialId === trial.trialId && sample.scored)
    .sort((a, b) => a.videoTimeMs - b.videoTimeMs);
  const boxes = bundle.boxes.filter(box => box.trialId === trial.trialId && box.visible);
  const results = [
    ["Source frames", trial.sourceVideoFrames > 0 ? formatNumber(trial.sourceVideoFrames) : "Unavailable"],
    ["Captured", formatNumber(trial.capturedGazeSamples)], ["Valid", formatNumber(trial.validSamples)],
    ["Excluded", formatNumber(trial.excludedGazeSamples)],
    ["Exact hits", trial.strictHits + " / " + trial.validSamples + " · " + percent(trial.strictHitRate)],
    ["Tolerance hits", trial.tolerantHits + " / " + trial.validSamples + " · " + percent(trial.tolerantHitRate)],
    ["Mean distance", trial.validSamples ? formatNumber(trial.meanDistancePx) + " px" : "Unavailable"],
    ["Relative mean distance", trial.meanDistanceViewportDiagonalPct == null ? "Unavailable" :
      trial.meanDistanceViewportDiagonalPct.toFixed(2) + "% of viewport diagonal"],
    ["Device", geometry?.model ?? "Unavailable"],
    ["Reported display", geometry ? geometry.displayWidthPx + " × " + geometry.displayHeightPx : "Unavailable"],
  ];
  main.replaceChildren(el("a", {class: "back", href: "#session/" + summary.sessionId}, "← " + sessionTitle(summary)),
    el("h1", {}, trial.videoName),
    el("p", {class: "muted"}, modeName(summary.presentationMode) + " · " +
      (trial.endedBySwipe ? "Swiped at " + seconds(trial.presentedDurationMs) : seconds(trial.presentedDurationMs) + " viewed")),
    el("div", {class: "replay"}, el("div", {class: "panel"}, stage,
      el("div", {class: "playback"}, play, timeline, time), controls),
      el("div", {}, metrics("Trial results", results))));

  const ctx = canvas.getContext("2d");
  function draw() {
    const width = stage.clientWidth;
    const height = stage.clientHeight;
    if (!width || !height) return;
    const ratio = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(height * ratio);
    }
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, width, height);
    const recordedVideo = summary.experimentSnapshot.videos.find(item => item.id === trial.videoId);
    const sourceWidth = viewport?.viewportWidthPx || video.videoWidth || recordedVideo?.width || 1;
    const sourceHeight = viewport?.viewportHeightPx || video.videoHeight || recordedVideo?.height || 1;
    const rect = viewport?.videoRectPx ?? {left: 0, top: 0, right: sourceWidth, bottom: sourceHeight};
    const scale = Math.min(width / sourceWidth, height / sourceHeight);
    const left = (width - sourceWidth * scale) / 2;
    const top = (height - sourceHeight * scale) / 2;
    const vw = (rect.right - rect.left) * scale;
    const vh = (rect.bottom - rect.top) * scale;
    const x0 = left + rect.left * scale;
    const y0 = top + rect.top * scale;
    Object.assign(video.style, {left: x0 + "px", top: y0 + "px", width: vw + "px", height: vh + "px"});
    const ms = Math.round(video.currentTime * 1000);
    seek.value = ms;
    time.textContent = seconds(ms) + " / " + seconds(trial.videoDurationMs);
    const afterSwipe = trial.endedBySwipe && ms > trial.presentedDurationMs;
    notice.hidden = !afterSwipe;
    if (afterSwipe) return;
    let last;
    const radius = Math.max(12, Math.min(sourceWidth, sourceHeight) * scale * 0.065);
    ctx.save();
    ctx.beginPath();
    ctx.rect(left, top, sourceWidth * scale, sourceHeight * scale);
    ctx.clip();
    if (layers.heat.checked || layers.point.checked) {
      for (const point of samples) {
        if (point.videoTimeMs > ms) break;
        if (!Number.isFinite(point.gazeXVideo) || !Number.isFinite(point.gazeYVideo)) continue;
        const x = x0 + point.gazeXVideo * vw;
        const y = y0 + point.gazeYVideo * vh;
        last = {x, y, time: point.videoTimeMs};
        if (layers.heat.checked) {
          const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius);
          gradient.addColorStop(0, "rgba(255,72,38,.22)");
          gradient.addColorStop(0.4, "rgba(255,191,48,.13)");
          gradient.addColorStop(1, "rgba(255,191,48,0)");
          ctx.fillStyle = gradient;
          ctx.fillRect(x - radius, y - radius, radius * 2, radius * 2);
        }
      }
    }
    if (layers.point.checked && last && ms - last.time <= 250) {
      ctx.beginPath();
      ctx.arc(last.x, last.y, 5, 0, Math.PI * 2);
      ctx.fillStyle = "white";
      ctx.fill();
      ctx.strokeStyle = "#222";
      ctx.stroke();
    }
    for (const box of boxes) {
      const atEnd = ms === trial.videoDurationMs && ms === box.endMs;
      if (ms < box.startMs || (ms >= box.endMs && !atEnd)) continue;
      const color = box.colorArgb == null ? "#64ddc8" :
        "#" + ((box.colorArgb & 0xffffff).toString(16).padStart(6, "0"));
      for (const [key, values] of [["tolerant", box.tolerantVideo], ["exact", box.exactVideo]]) {
        if (!layers[key].checked || !values) continue;
        const x = x0 + values.left * vw;
        const y = y0 + values.top * vh;
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.setLineDash(key === "tolerant" ? [5, 4] : []);
        ctx.strokeRect(x, y, (values.right - values.left) * vw, (values.bottom - values.top) * vh);
        if (key === "exact" || !layers.exact.checked) {
          ctx.font = "12px system-ui";
          ctx.fillStyle = "#10191e";
          ctx.fillRect(x, y, ctx.measureText(box.label ?? "").width + 8, 16);
          ctx.fillStyle = "white";
          ctx.fillText(box.label ?? "", x + 4, y + 12);
        }
      }
    }
    ctx.restore();
  }
  seek.addEventListener("input", () => { video.currentTime = Number(seek.value) / 1000; draw(); });
  let frame;
  const tick = () => { draw(); if (!video.paused) frame = requestAnimationFrame(tick); };
  video.addEventListener("play", () => { play.textContent = "Pause"; tick(); });
  video.addEventListener("pause", () => { play.textContent = "Play"; cancelAnimationFrame(frame); draw(); });
  video.addEventListener("seeked", draw);
  video.addEventListener("loadedmetadata", draw);
  video.addEventListener("error", () => { time.textContent = "Video unavailable or unsupported by this browser"; });
  const resize = new ResizeObserver(draw);
  resize.observe(stage);
  cleanup = () => { video.pause(); resize.disconnect(); cancelAnimationFrame(frame); };
  draw();
}

async function navigate() {
  cleanup();
  cleanup = () => {};
  const token = ++routeVersion;
  main.setAttribute("aria-busy", "true");
  main.replaceChildren(el("p", {class: "muted", role: "status"}, "Loading…"));
  try {
    const match = /^#session\/([0-9a-f-]+)(?:\/(\d+))?$/.exec(location.hash);
    const participant = /^#participant\/([A-Za-z0-9_-]{1,32})$/.exec(location.hash);
    if (!match) {
      const data = await get("/api/sessions?includeDeleted=1");
      if (token === routeVersion) {
        if (participant) participantDetail(data, participant[1]);
        else sessions(data);
      }
    } else {
      const bundle = sessionCache?.summary.sessionId === match[1] ? sessionCache :
        await get("/api/sessions/" + match[1]);
      if (token !== routeVersion) return;
      sessionCache = bundle;
      const trial = bundle.summary.trials.find(item => item.trialIndex === Number(match[2]));
      if (match[2] && !trial) throw new Error("This trial is unavailable.");
      if (trial) trialDetail(bundle, trial);
      else testDetail(bundle);
    }
  } catch (error) {
    if (token === routeVersion) main.replaceChildren(el("a", {href: "#sessions"}, "← Sessions"), el("p", {}, error.message));
  } finally {
    if (token === routeVersion) main.setAttribute("aria-busy", "false");
  }
}
window.addEventListener("hashchange", navigate);
navigate();
