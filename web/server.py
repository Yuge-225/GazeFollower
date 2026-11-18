"""Private GazeFollower session dashboard. Start with python3 web/server.py."""
import argparse
import base64
import csv
import hashlib
import hmac
import io
import json
import math
import os
import re
import secrets
import shutil
import tempfile
from threading import Lock
import zipfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import CookieError, SimpleCookie
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from spreadsheet import workbook

HASH = re.compile(r"[a-f0-9]{64}")
SESSION = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
CODE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
BOX_EDGES = ("left", "top", "right", "bottom")
PUBLIC = Path(__file__).parent / "public"
SESSION_LIMIT = 32 * 1024 * 1024
VIDEO_LIMIT = 512 * 1024 * 1024


def items(value, key):
    result = value.get(key)
    if not isinstance(result, list):
        raise ValueError(f"Expected {key} to be a list.")
    return result


def matches(pattern, value):
    return isinstance(value, str) and pattern.fullmatch(value)


def identifier(summary):
    return {
        "participantCode": summary["participantCode"],
        "experimentId": summary["experimentId"],
        "experimentVersion": summary["experimentVersion"],
        "sessionId": summary["sessionId"],
    }


def geometry_boxes(summary):
    snapshot = summary["experimentSnapshot"]
    labels = {label["id"]: label for label in snapshot.get("labels", [])}
    videos = {video["id"]: video for video in snapshot["videos"]}
    for trial in summary["trials"]:
        video = videos[trial["videoId"]]
        region = (trial.get("geometry") or {}).get("videoRectPx")
        expansion = trial.get("toleranceExpansionFraction", 0.20)
        for track in [video, *video.get("additionalAois", [])]:
            label = labels.get(track.get("labelId"), {})
            frames = sorted(track.get("keyframes", []), key=lambda frame: frame["timestampMs"])
            for index, frame in enumerate(frames):
                # Android uses the first keyframe before any later changes.
                start = frame["timestampMs"] if index else 0
                end = frames[index + 1]["timestampMs"] if index + 1 < len(frames) else trial["videoDurationMs"]
                exact = frame.get("roi") if frame.get("visible", True) else None
                tolerant = exact_px = tolerant_px = None
                if exact:
                    dx = (exact["right"] - exact["left"]) * expansion / 2
                    dy = (exact["bottom"] - exact["top"]) * expansion / 2
                    tolerant = dict(zip(BOX_EDGES, [
                        max(0, exact["left"] - dx), max(0, exact["top"] - dy),
                        min(1, exact["right"] + dx), min(1, exact["bottom"] + dy),
                    ]))
                    if region:
                        exact_px = to_pixels(exact, region)
                        tolerant_px = to_pixels(tolerant, region)
                yield {
                    "trialId": trial["trialId"], "trialIndex": trial["trialIndex"], "videoId": video["id"],
                    "boxId": "primary" if track is video else track["id"],
                    "labelId": track.get("labelId"), "label": label.get("name", track.get("labelId")),
                    "colorArgb": label.get("colorArgb"), "startMs": start, "endMs": end,
                    "visible": bool(exact), "exactVideo": exact, "tolerantVideo": tolerant,
                    "exactPx": exact_px, "tolerantPx": tolerant_px,
                }


def to_pixels(box, region):
    width, height = region["right"] - region["left"], region["bottom"] - region["top"]
    return {
        "left": region["left"] + box["left"] * width,
        "top": region["top"] + box["top"] * height,
        "right": region["left"] + box["right"] * width,
        "bottom": region["top"] + box["bottom"] * height,
    }


def legacy_samples(source, trials):
    by_video = {trial["videoId"]: trial for trial in trials}
    for row in csv.DictReader(io.StringIO(source)):
        trial = by_video[row["video_id"]]
        yield {
            "trialIndex": trial["trialIndex"], "videoId": row["video_id"],
            "videoTimeMs": int(row["video_time_ms"]), "scored": True,
            "gazeXPx": float(row["gaze_x_px"]), "gazeYPx": float(row["gaze_y_px"]),
            "gazeXVideo": float(row["video_x_normalized"]), "gazeYVideo": float(row["video_y_normalized"]),
            "strictHit": row["strict_hit"] == "true", "tolerantHit": row["tolerant_hit"] == "true",
            "distancePx": float(row["distance_px"]), "assignedLabelId": row["assigned_label_id"],
            "assignedLabel": row.get("matched_aois"),
        }


def prepare_bundle(body, assets):
    summary = body["summary"]
    summary.pop("research", None)
    session_id = summary["sessionId"]
    if not matches(SESSION, session_id) or not matches(CODE, summary["participantCode"]):
        raise ValueError("Invalid session ID or participant code.")
    identifier(summary)
    if summary["presentationMode"] not in ("READY", "SCROLL"):
        raise ValueError("Invalid presentation mode.")
    videos = {video["id"]: video for video in items(summary["experimentSnapshot"], "videos")}
    trials = items(summary, "trials")
    for index, trial in enumerate(trials, 1):
        video = videos[trial["videoId"]]
        asset = video["assetHash"]
        if not matches(HASH, asset) or not (assets / asset).is_file():
            raise ValueError("A stimulus video is missing. Retry the upload from the app.")
        video.pop("localPath", None)
        trial.update(trialIndex=index, trialId=f"{session_id}-{index}", assetHash=asset)
        geometry = trial.get("geometry")
        if geometry and trial["validSamples"]:
            diagonal = math.hypot(geometry["viewportWidthPx"], geometry["viewportHeightPx"])
            if diagonal:
                trial["meanDistanceViewportDiagonalPct"] = 100 * trial["meanDistancePx"] / diagonal
    legacy = "legacyGazeCsv" in body
    samples = list(legacy_samples(body["legacyGazeCsv"], trials)) if legacy else items(body, "samples")
    by_index = {trial["trialIndex"]: trial for trial in trials}
    for sample in samples:
        trial = by_index[sample["trialIndex"]]
        if sample["videoId"] != trial["videoId"]:
            raise ValueError("A gaze sample does not match its trial.")
        sample["trialId"] = trial["trialId"]
        geometry = trial.get("geometry")
        if geometry and sample.get("distancePx") is not None:
            diagonal = math.hypot(geometry["viewportWidthPx"], geometry["viewportHeightPx"])
            if diagonal:
                sample["distanceViewportDiagonalPct"] = 100 * sample["distancePx"] / diagonal
    return {
        "schemaVersion": 1, "sampleCoverage": "scored_only" if legacy else "all_captured",
        "summary": summary, "samples": samples, "boxes": list(geometry_boxes(summary)),
    }


def flatten(data, prefix=""):
    result = {}
    for key, value in data.items():
        field = f"{prefix}{key}"
        if isinstance(value, dict):
            result.update(flatten(value, f"{field}_"))
        elif isinstance(value, list):
            result[field] = json.dumps(value, ensure_ascii=False)
        else:
            result[field] = value
    return result


def test_workbook(bundles):
    tables = {
        "Tests": (["Test ID", "Participant", "Completed UTC", "experimentName", "experimentVersion", "presentationMode"], []),
        "Trials": (["Test ID", "Trial", "videoName"], []),
        "Gaze Samples": (["Test ID", "Trial", "videoTimeMs"], []),
        "AOI Boxes": (["Test ID", "Trial", "label", "boxId", "startMs", "endMs"], []),
        "Attention": (["Test ID", "Trial", "labelName"], []),
        "Validation Samples": (["Test ID"], []),
    }
    ids = [bundle["summary"]["sessionId"].replace("-", "") for bundle in bundles]

    def add(sheet, values):
        tables[sheet][1].append(flatten(values))

    def detail(record):
        return {key: value for key, value in record.items()
                if key not in ("trialId", "trialIndex", "videoId", "assetHash")}

    for bundle, uid in zip(bundles, ids):
        summary = bundle["summary"]
        # Keep the full ID once so a row still traces back to the stored session.
        length = next(length for length in range(8, 33)
                      if sum(other.startswith(uid[:length]) for other in ids) == 1)
        test = {"Test ID": uid[:length]}
        validation = summary.get("validation") or {}
        research = summary.get("research") or {}
        add("Tests", {
            **test, "Participant": summary["participantCode"],
            "Completed UTC": datetime.fromtimestamp(summary["completedAtMs"] / 1000, timezone.utc),
            "experimentName": summary.get("experimentName"), "experimentVersion": summary["experimentVersion"],
            "presentationMode": summary["presentationMode"], "trials": len(summary["trials"]),
            "sampleCoverage": bundle.get("sampleCoverage"),
            **({"testName": research["name"]} if research.get("name") else {}),
            **({"researchNotes": research["notes"]} if research.get("notes") else {}),
            "validation": {key: value for key, value in validation.items() if key != "samples"},
            "sessionId": summary["sessionId"],
        })
        for trial in summary["trials"]:
            trial_key = {**test, "Trial": trial["trialIndex"]}
            trial_fields = detail(trial)
            trial_fields.pop("attentionByLabel", None)
            add("Trials", {**trial_key, **trial_fields})
            for label in trial.get("attentionByLabel", []):
                add("Attention", {**trial_key, **label})
        for sample in bundle["samples"]:
            add("Gaze Samples", {**test, "Trial": sample["trialIndex"], **detail(sample)})
        for box in bundle["boxes"]:
            add("AOI Boxes", {**test, "Trial": box["trialIndex"], **detail(box)})
        for sample in validation.get("samples", []):
            add("Validation Samples", {**test, **sample})
    return workbook([
        (name, list(dict.fromkeys([*columns, *(field for row in rows for field in row)])), rows)
        for name, (columns, rows) in tables.items()
    ])


def session_filename(summary):
    completed = datetime.fromtimestamp(summary["completedAtMs"] / 1000, timezone.utc)
    version = re.sub(r"[^A-Za-z0-9_-]", "_", str(summary["experimentVersion"]))
    return (f'{summary["participantCode"]}_V{version}_{summary["presentationMode"].lower()}'
            f'_{completed:%Y%m%dT%H%M%SZ}_{summary["sessionId"]}')


def select_trials(bundle, indices):
    summary = bundle["summary"]
    trials = [trial for trial in summary["trials"] if trial["trialIndex"] in indices]
    if {trial["trialIndex"] for trial in trials} != indices:
        raise ValueError("One or more selected videos are unavailable.")
    video_ids = {trial["videoId"] for trial in trials}
    snapshot = summary["experimentSnapshot"]
    return {
        **bundle,
        "summary": {**summary, "trials": trials,
                    "experimentSnapshot": {**snapshot, "videos": [video for video in snapshot["videos"]
                                                                  if video["id"] in video_ids]}},
        "samples": [sample for sample in bundle["samples"] if sample["trialIndex"] in indices],
        "boxes": [box for box in bundle["boxes"] if box["trialIndex"] in indices],
    }


def csv_zip(bundle):
    summary = bundle["summary"]
    identity = identifier(summary)
    trials = summary["trials"]
    tables = {
        "session.csv": [{**identity, "sampleCoverage": bundle["sampleCoverage"], **{
            key: value for key, value in summary.items() if key not in ("trials", "experimentSnapshot")
        }}],
        "trials.csv": [{**identity, **trial} for trial in trials],
        "gaze_samples.csv": [{**identity, **sample} for sample in bundle["samples"]],
        "aoi_boxes.csv": [{**identity, **box} for box in bundle["boxes"]],
        "attention_by_label.csv": [{**identity, "trialId": trial["trialId"], "trialIndex": trial["trialIndex"],
                                   **label} for trial in trials for label in trial.get("attentionByLabel", [])],
        "validation_samples.csv": [{**identity, **sample} for sample in
                                   (summary.get("validation") or {}).get("samples", [])],
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, rows in tables.items():
            rows = [flatten(row) for row in rows]
            fields = list(dict.fromkeys(key for row in rows for key in row)) or list(identity)
            target = io.StringIO(newline="")
            writer = csv.DictWriter(target, fields)
            writer.writeheader()
            for row in rows:
                writer.writerow({
                    key: "'" + value if isinstance(value, str) and value.lstrip().startswith(("=", "+", "-", "@"))
                    else value for key, value in row.items()
                })
            archive.writestr(name, target.getvalue())
    return output.getvalue()


def store_once(destination, temporary):
    try:
        os.link(temporary, destination)
        return True
    except FileExistsError:
        return False


class DashboardServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, root, password):
        self.assets = root / "videos"
        self.sessions = root / "sessions"
        self.assets.mkdir(parents=True, exist_ok=True)
        self.sessions.mkdir(parents=True, exist_ok=True)
        self.credential = b"Basic " + base64.b64encode(f"researcher:{password}".encode())
        self.browser_token = secrets.token_urlsafe(32).encode()
        self.session_edits = Lock()
        super().__init__(address, DashboardHandler)


class DashboardHandler(BaseHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        super().end_headers()

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_PUT(self):
        self.dispatch("PUT")

    def dashboard_write_is_same_origin(self):
        origin = self.headers.get("Origin")
        fetch_site = self.headers.get("Sec-Fetch-Site")
        if fetch_site not in (None, "same-origin"):
            return False
        if origin is None:
            return fetch_site == "same-origin"
        scheme = "https" if self.headers.get("X-Forwarded-Proto") == "https" else "http"
        host = self.headers.get("Host")
        return bool(host) and origin.lower() == f"{scheme}://{host.lower()}"

    def dispatch(self, method):
        url = urlsplit(self.path)
        path = url.path
        management = re.fullmatch(r"/api/sessions/([^/]+)/manage", path) if method == "POST" else None
        try:
            if method != "GET" and self.headers.get("Sec-Fetch-Site") == "cross-site":
                return self.send_json(403, {"error": "Cross-site writes are disabled."})
            if method == "POST" and path == "/login":
                form = parse_qs(self.rfile.read(self.length(4096)).decode())
                credential = b"Basic " + base64.b64encode(f"researcher:{form.get('password', [''])[0]}".encode())
                if not hmac.compare_digest(self.server.credential, credential):
                    return self.respond(401, (PUBLIC / "login.html").read_bytes().replace(
                        b"Enter the dashboard password.", b"Password rejected. Try again."), "text/html; charset=utf-8")
                secure = "; Secure" if self.headers.get("X-Forwarded-Proto") == "https" else ""
                cookie = "gaze_dashboard=" + self.server.browser_token.decode() + "; Path=/; HttpOnly; SameSite=Strict" + secure
                return self.respond(303, b"", "text/plain", {"Location": "/", "Set-Cookie": cookie})
            credential = self.headers.get("Authorization", "").encode()
            signed_in = hmac.compare_digest(self.server.credential, credential)
            if (method == "GET" or (management and self.dashboard_write_is_same_origin())) and not signed_in:
                cookie = SimpleCookie()
                try:
                    cookie.load(self.headers.get("Cookie", ""))
                except CookieError:
                    cookie.clear()
                token = cookie.get("gaze_dashboard")
                signed_in = token is not None and hmac.compare_digest(self.server.browser_token, token.value.encode())
                if not signed_in and path == "/":
                    return self.respond(200, (PUBLIC / "login.html").read_bytes(), "text/html; charset=utf-8")
                if path == "/style.css":
                    signed_in = True
            if not signed_in:
                return self.send_json(401, {"error": "Sign in as researcher."})
            if management:
                return self.manage_test(self.session_path(management[1]), self.read_json())
            if method == "POST" and path == "/api/check":
                body = self.read_json()
                session = self.session_path(body["sessionId"])
                hashes = items(body, "assetHashes")
                if len(hashes) > 50 or any(not matches(HASH, item) for item in hashes):
                    raise ValueError("Invalid stimulus video fingerprint.")
                return self.send_json(200, {
                    "sessionExists": session.is_file(),
                    "missingAssets": [item for item in dict.fromkeys(hashes) if not (self.server.assets / item).is_file()],
                })
            if method == "POST" and path == "/api/sessions":
                body = self.read_json()
                destination = self.session_path(body["summary"]["sessionId"])
                if destination.exists():
                    return self.send_json(200, {"alreadyUploaded": True})
                bundle = prepare_bundle(body, self.server.assets)
                with tempfile.NamedTemporaryFile(dir=self.server.sessions) as temporary:
                    temporary.write(self.json_bytes(bundle))
                    temporary.flush()
                    created = store_once(destination, temporary.name)
                return self.send_json(201 if created else 200, {"alreadyUploaded": not created})
            if method == "PUT" and path.startswith("/api/assets/"):
                return self.upload_asset(path.rsplit("/", 1)[-1])
            if method != "GET":
                return self.send_json(404, {"error": "Unknown endpoint."})
            if path == "/api/sessions":
                summaries = []
                include_deleted = parse_qs(url.query).get("includeDeleted") == ["1"]
                for file in self.server.sessions.glob("*.json"):
                    bundle = json.loads(file.read_bytes())
                    summary = bundle["summary"]
                    research = summary.get("research") or {}
                    if research.get("deleted") and not include_deleted:
                        continue
                    summaries.append({
                        **identifier(summary), "experimentName": summary.get("experimentName", ""),
                        "presentationMode": summary["presentationMode"], "completedAtMs": summary["completedAtMs"],
                        "trialCount": len(summary["trials"]),
                        "validSamples": sum(trial["validSamples"] for trial in summary["trials"]),
                        "research": research,
                        "trials": [{"index": trial["trialIndex"], "name": trial.get("videoName", f'Video {trial["trialIndex"]}')}
                                   for trial in summary["trials"]],
                    })
                return self.send_json(200, sorted(summaries, key=lambda item: item["completedAtMs"], reverse=True))
            participant_export = re.fullmatch(r"/api/participants/([^/]+)/export\.(json|xlsx)", path)
            if participant_export:
                code, extension = participant_export.groups()
                if not matches(CODE, code):
                    raise ValueError("Invalid participant code.")
                selection = parse_qs(url.query, keep_blank_values=True).get("trial")
                return self.participant_export(code, extension, selection)
            if path.startswith("/api/sessions/"):
                parts = path.split("/")
                file = self.session_path(parts[3])
                if not file.exists():
                    return self.send_json(404, {"error": "Session is unavailable."})
                bundle = json.loads(file.read_bytes())
                if (bundle["summary"].get("research") or {}).get("deleted"):
                    return self.send_json(404, {"error": "This test was deleted. Restore it from the participant page."})
                if len(parts) == 4:
                    return self.send_json(200, bundle)
                if len(parts) == 5 and parts[4] in ("export.json", "export.xlsx", "export.zip"):
                    return self.export_file(session_filename(bundle["summary"]), parts[4].split(".")[1], [bundle], bundle)
            if path.startswith("/media/"):
                digest = path.rsplit("/", 1)[-1]
                if matches(HASH, digest):
                    return self.send_video(self.server.assets / digest)
            static = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
                      "/style.css": ("style.css", "text/css")}
            if path in static:
                file, kind = static[path]
                return self.respond(200, (PUBLIC / file).read_bytes(), f"{kind}; charset=utf-8")
            self.send_json(404, {"error": "Page is unavailable."})
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except (ValueError, KeyError, TypeError, AttributeError, OverflowError) as error:
            self.send_json(400, {"error": f"Invalid request. {error}"})

    def session_path(self, session):
        if not matches(SESSION, session):
            raise ValueError("Invalid session ID.")
        return self.server.sessions / f"{session}.json"

    def manage_test(self, file, body):
        action = body.get("action")
        if action not in ("edit", "delete", "restore"):
            raise ValueError("Unknown test action.")
        with self.server.session_edits:
            if not file.exists():
                return self.send_json(404, {"error": "Session is unavailable."})
            bundle = json.loads(file.read_bytes())
            research = bundle["summary"].setdefault("research", {})
            if action == "edit":
                name, notes = body.get("name", ""), body.get("notes", "")
                if not isinstance(name, str) or not isinstance(notes, str) or len(name) > 120 or len(notes) > 2000:
                    raise ValueError("Use up to 120 characters for the test name and 2,000 for notes.")
                research.update(name=name.strip(), notes=notes.strip())
            else:
                research["deleted"] = action == "delete"
            with tempfile.TemporaryDirectory(dir=self.server.sessions) as temporary:
                updated = Path(temporary) / file.name
                updated.write_bytes(self.json_bytes(bundle))
                os.replace(updated, file)
        return self.send_json(200, research)

    def participant_export(self, code, extension, selection):
        wanted = {}
        for value in selection or []:
            session, _, index = value.partition(":")
            if not matches(SESSION, session) or not index.isascii() or not index.isdecimal() or len(index) > 8:
                raise ValueError("Invalid video selection.")
            wanted.setdefault(session, set()).add(int(index))
        bundles = []
        for file in self.server.sessions.glob("*.json"):
            if wanted and file.stem not in wanted:
                continue
            bundle = json.loads(file.read_bytes())
            summary = bundle["summary"]
            if summary["participantCode"] != code or (summary.get("research") or {}).get("deleted"):
                continue
            bundles.append(select_trials(bundle, wanted[file.stem]) if wanted else bundle)
        if wanted and len(bundles) != len(wanted):
            raise ValueError("One or more selected tests are unavailable for this participant.")
        if not bundles:
            return self.send_json(404, {"error": "No uploaded tests for this participant."})
        bundles.sort(key=lambda item: (item["summary"]["completedAtMs"], item["summary"]["sessionId"]))
        exported = datetime.now(timezone.utc)
        scope = "selected-videos" if wanted else "all-tests"
        name = f"{code}_{scope}_{exported:%Y%m%dT%H%M%S%fZ}"
        payload = {"schemaVersion": 1, "participantCode": code, "exportedAtUtc": exported.isoformat(),
                   "tests": bundles}
        return self.export_file(name, extension, bundles, payload)

    def export_file(self, filename, extension, bundles, payload):
        if extension == "xlsx":
            data = test_workbook(bundles)
            kind = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        elif extension == "zip":
            data = csv_zip(bundles[0])
            kind = "application/zip"
        else:
            data = self.json_bytes(payload)
            kind = "application/json"
        return self.respond(200, data, kind, {"Content-Disposition": f'attachment; filename="{filename}.{extension}"'})

    def length(self, limit):
        length = int(self.headers.get("Content-Length", "-1"))
        if not 0 < length <= limit:
            raise ValueError(f"Invalid request size. Maximum: {limit // (1024 * 1024)} MB.")
        return length

    def read_json(self):
        return json.loads(self.rfile.read(self.length(SESSION_LIMIT)))

    def upload_asset(self, digest):
        if not matches(HASH, digest):
            raise ValueError("Invalid stimulus video fingerprint.")
        remaining = self.length(VIDEO_LIMIT)
        received = hashlib.sha256()
        with tempfile.NamedTemporaryFile(dir=self.server.assets) as temporary:
            while remaining:
                chunk = self.rfile.read(min(remaining, 65536))
                if not chunk:
                    raise ValueError("Incomplete stimulus video.")
                temporary.write(chunk)
                received.update(chunk)
                remaining -= len(chunk)
            if received.hexdigest() != digest:
                raise ValueError("Stimulus video fingerprint did not match.")
            temporary.flush()
            created = store_once(self.server.assets / digest, temporary.name)
        self.send_json(201 if created else 200, {"stored": True})

    def send_video(self, file):
        if not file.is_file():
            return self.send_json(404, {"error": "Stimulus video is unavailable."})
        size = file.stat().st_size
        start, end = 0, size - 1
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header)
            if match and match[1]:
                start = int(match[1])
                end = min(end, int(match[2])) if match[2] else end
            elif match and match[2]:
                start = max(0, size - int(match[2]))
            else:
                start = size
            if not start <= end:
                return self.respond(416, b"", "text/plain", {"Content-Range": f"bytes */{size}"})
        with file.open("rb") as stream:
            header = stream.read(32)
            kind = "video/webm" if header.startswith(bytes.fromhex("1a45dfa3")) else "video/mp4"
            if header[8:12] == b"qt  ":
                kind = "video/quicktime"
            self.send_response(206 if range_header else 200)
            self.send_header("Content-Type", kind)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if range_header:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            stream.seek(start)
            if not range_header:
                shutil.copyfileobj(stream, self.wfile)
            else:
                remaining = end - start + 1
                while remaining:
                    chunk = stream.read(min(remaining, 65536))
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

    @staticmethod
    def json_bytes(data):
        return json.dumps(data, ensure_ascii=False, allow_nan=False).encode()

    def send_json(self, status, data, headers=None):
        self.respond(status, self.json_bytes(data), "application/json", headers)

    def respond(self, status, content, kind, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(content)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="Address to bind (default: loopback).")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--data-dir", type=Path, default=Path("web/.data"))
    args = parser.parse_args()
    password = os.environ.get("GAZEFOLLOWER_WEB_PASSWORD")
    if not password:
        parser.error("Set GAZEFOLLOWER_WEB_PASSWORD before starting the website.")
    with DashboardServer((args.host, args.port), args.data_dir, password) as server:
        print(f"GazeFollower dashboard: http://{args.host}:{server.server_port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
