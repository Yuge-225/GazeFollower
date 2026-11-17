import base64
import csv
import hashlib
import io
import json
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree

from server import DashboardServer


SESSION = "2f050fb5-2a95-460b-a091-21a224bf9935"
VIDEO = b"synthetic stimulus bytes for range tests"
HASH = hashlib.sha256(VIDEO).hexdigest()


def fixture():
    return {
        "summary": {
            "sessionId": SESSION, "participantCode": "P014", "experimentId": "study",
            "experimentVersion": 3, "presentationMode": "SCROLL", "completedAtMs": 1000,
            "validation": {"passed": True, "sampleCount": 40},
            "experimentSnapshot": {
                "labels": [{"id": "head", "name": "Head"}],
                "videos": [{"id": "video-1", "assetHash": HASH, "labelId": "head", "keyframes": [
                    {"timestampMs": 100, "roi": {"left": 0, "top": 0.2, "right": 0.4, "bottom": 0.4}},
                    {"timestampMs": 1500, "visible": False},
                ]}],
            },
            "trials": [{
                "videoId": "video-1", "videoDurationMs": 3000, "validSamples": 1, "meanDistancePx": 10,
                "capturedGazeSamples": 2, "excludedGazeSamples": 1,
                "toleranceExpansionFraction": 0.20, "endedBySwipe": True, "presentedDurationMs": 1600,
                "geometry": {"viewportWidthPx": 1000, "viewportHeightPx": 2000,
                             "videoRectPx": {"left": 0, "top": 500, "right": 1000, "bottom": 1500}},
            }],
        },
        "samples": [
            {"trialIndex": 1, "videoId": "video-1", "videoTimeMs": 200, "scored": False,
             "excludedReason": "START_DELAY"},
            {"trialIndex": 1, "videoId": "video-1", "videoTimeMs": 750, "scored": True,
             "gazeXVideo": 0.41, "gazeYVideo": 0.3, "distancePx": 10},
        ],
    }


class DashboardIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.data = tempfile.TemporaryDirectory()
        self.server = DashboardServer(("127.0.0.1", 0), Path(self.data.name), "integration-test")
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.data.cleanup()

    def request(self, path, method="GET", payload=None, headers=None, auth=True):
        headers = dict(headers or {})
        if auth:
            headers["Authorization"] = "Basic " + base64.b64encode(b"researcher:integration-test").decode()
        if isinstance(payload, dict):
            headers["Content-Type"] = "application/json"
            payload = json.dumps(payload).encode()
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", payload, headers, method=method)
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def check_missing(self):
        status, _, data = self.request("/api/check", "POST", {"sessionId": SESSION, "assetHashes": [HASH]})
        self.assertEqual(200, status)
        return json.loads(data)

    def test_manual_upload_deduplicates_and_exports_replay_data(self):
        self.assertEqual(401, self.request("/api/sessions", auth=False)[0])
        self.assertEqual(200, self.request("/")[0])
        self.assertEqual({"sessionExists": False, "missingAssets": [HASH]}, self.check_missing())
        self.assertEqual(400, self.request("/api/sessions", "POST", fixture())[0])
        asset = "/api/assets/" + HASH
        self.assertEqual(400, self.request(asset, "PUT", b"incomplete")[0])
        self.assertEqual([HASH], self.check_missing()["missingAssets"])
        self.assertEqual(201, self.request(asset, "PUT", VIDEO)[0])
        self.assertEqual({"sessionExists": False, "missingAssets": []}, self.check_missing())
        self.assertEqual(201, self.request("/api/sessions", "POST", fixture())[0])
        self.assertEqual({"sessionExists": True, "missingAssets": []}, self.check_missing())
        duplicate = fixture()
        duplicate["summary"]["participantCode"] = "DIFFERENT"
        self.assertEqual({"alreadyUploaded": True}, json.loads(self.request("/api/sessions", "POST", duplicate)[2]))
        self.assertEqual(206, self.request("/media/" + HASH, headers={"Range": "bytes=1-4"})[0])
        self.assertEqual(VIDEO[1:5], self.request("/media/" + HASH, headers={"Range": "bytes=1-4"})[2])
        self.assertEqual(416, self.request("/media/" + HASH, headers={"Range": "bytes=999-"})[0])

        sessions = json.loads(self.request("/api/sessions")[2])
        self.assertEqual(["P014"], [session["participantCode"] for session in sessions])
        path = "/api/sessions/" + SESSION
        exported = json.loads(self.request(path + "/export.json")[2])
        exact, hidden = exported["boxes"]
        self.assertEqual((0, 1500), (exact["startMs"], exact["endMs"]))
        self.assertEqual(0, exact["tolerantPx"]["left"])
        self.assertAlmostEqual(440, exact["tolerantPx"]["right"])
        self.assertEqual(700, exact["exactPx"]["top"])
        self.assertFalse(hidden["visible"])
        self.assertEqual(1600, exported["summary"]["trials"][0]["presentedDurationMs"])
        with zipfile.ZipFile(io.BytesIO(self.request(path + "/export.zip")[2])) as archive:
            rows = list(csv.DictReader(io.StringIO(archive.read("gaze_samples.csv").decode())))
            self.assertEqual(2, len(rows))
            self.assertEqual("START_DELAY", rows[0]["excludedReason"])
            self.assertEqual(SESSION + "-1", rows[1]["trialId"])
            self.assertEqual("P014", rows[1]["participantCode"])

    def test_historical_csv_labels_coverage_and_leaves_unknown_geometry_blank(self):
        body = fixture()
        del body["samples"]
        del body["summary"]["trials"][0]["geometry"]
        body["legacyGazeCsv"] = (
            "video_id,video_time_ms,gaze_x_px,gaze_y_px,video_x_normalized,video_y_normalized,"
            "strict_hit,tolerant_hit,distance_px,matched_aois,assigned_label_id\n"
            "video-1,750,410,800,.41,.3,false,true,10,Outside AOIs,OUTSIDE\n"
        )
        self.assertEqual(201, self.request("/api/assets/" + HASH, "PUT", VIDEO)[0])
        self.assertEqual(201, self.request("/api/sessions", "POST", body)[0])
        exported = json.loads(self.request("/api/sessions/" + SESSION)[2])
        self.assertEqual("scored_only", exported["sampleCoverage"])
        self.assertIsNone(exported["boxes"][0]["exactPx"])
        self.assertEqual(1, exported["summary"]["trials"][0]["excludedGazeSamples"])

    def test_participant_export_keeps_all_their_tests_and_uses_short_join_keys(self):
        self.assertEqual(201, self.request("/api/assets/" + HASH, "PUT", VIDEO)[0])
        session_ids = [SESSION[:-1] + suffix for suffix in "1234"]
        for position, (participant, version, session_id) in enumerate(
                zip(("P01", "P01", "P02", "P010"), (3, 4, 3, 3), session_ids)):
            body = fixture()
            body["summary"].update(participantCode=participant, experimentVersion=version,
                                   sessionId=session_id, completedAtMs=1000 + position)
            body["summary"]["trials"][0].update(videoName="=Literal label", strictHitRate=0.5)
            self.assertEqual(201, self.request("/api/sessions", "POST", body)[0])

        path = "/api/participants/P01/export."
        self.assertEqual(401, self.request(path + "xlsx", auth=False)[0])
        self.assertEqual(404, self.request("/api/participants/P99/export.json")[0])
        status, headers, data = self.request(path + "json")
        self.assertEqual(200, status)
        self.assertRegex(headers["Content-Disposition"], r'filename="P01_all-tests_\d+T\d+Z\.json"')
        p01 = json.loads(data)
        self.assertEqual(session_ids[:2], [item["summary"]["sessionId"] for item in p01["tests"]])
        p02 = json.loads(self.request("/api/participants/P02/export.json")[2])
        self.assertEqual([session_ids[2]], [item["summary"]["sessionId"] for item in p02["tests"]])

        status, headers, data = self.request(path + "xlsx")
        self.assertEqual(200, status)
        self.assertRegex(headers["Content-Disposition"], r'filename="P01_all-tests_\d+T\d+Z\.xlsx"')
        ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            def sheet(index):
                xml = ElementTree.fromstring(archive.read(f"xl/worksheets/sheet{index}.xml"))
                return [{c.attrib["r"]: c for c in row} for row in xml.findall(".//s:row", ns)]

            def strings(row):
                return [cell.findtext("s:is/s:t", namespaces=ns) for cell in row.values()]

            tests, trials, gaze = sheet(1), sheet(2), sheet(3)
            test_keys = [strings(row)[0] for row in tests[1:]]
            self.assertEqual(2, len(set(test_keys)))
            self.assertEqual(test_keys, [strings(row)[0] for row in trials[1:]])
            self.assertEqual(["Test ID", "Trial", "Video time (ms)"], strings(gaze[0])[:3])
            self.assertNotIn("Participant", strings(gaze[0]))
            self.assertNotIn("Trial ID", strings(gaze[0]))
            self.assertEqual([key for key in test_keys for _ in range(2)], [strings(row)[0] for row in gaze[1:]])
            self.assertEqual("=Literal label", trials[1]["C2"].findtext("s:is/s:t", namespaces=ns))
            self.assertEqual(["Tests", "Trials", "Gaze Samples", "AOI Boxes", "Attention", "Validation Samples"],
                             [sheet.attrib["name"] for sheet in ElementTree.fromstring(
                                 archive.read("xl/workbook.xml")).findall(".//s:sheet", ns)])

        single = "/api/sessions/" + session_ids[0] + "/export.json"
        status, headers, data = self.request(single)
        self.assertEqual(200, status)
        self.assertEqual(f'attachment; filename="P01_V3_scroll_19700101T000001Z_{session_ids[0]}.json"',
                         headers["Content-Disposition"])
        self.assertEqual(session_ids[0], json.loads(data)["summary"]["sessionId"])

    def test_video_selection_and_recoverable_test_management(self):
        self.assertEqual(201, self.request("/api/assets/" + HASH, "PUT", VIDEO)[0])
        body = fixture()
        summary = body["summary"]
        second_video = {**summary["experimentSnapshot"]["videos"][0], "id": "video-2"}
        summary["experimentSnapshot"]["videos"].append(second_video)
        summary["trials"].append({**summary["trials"][0], "videoId": "video-2"})
        body["samples"].append({**body["samples"][-1], "trialIndex": 2, "videoId": "video-2"})
        self.assertEqual(201, self.request("/api/sessions", "POST", body)[0])

        export = "/api/participants/P014/export.json"
        selection = export + "?trial=" + SESSION + "%3A2"
        status, headers, data = self.request(selection)
        self.assertEqual(200, status)
        self.assertIn("P014_selected-videos_", headers["Content-Disposition"])
        selected = json.loads(data)["tests"][0]
        self.assertEqual([2], [trial["trialIndex"] for trial in selected["summary"]["trials"]])
        self.assertEqual([2], [sample["trialIndex"] for sample in selected["samples"]])
        self.assertEqual({2}, {box["trialIndex"] for box in selected["boxes"]})
        self.assertEqual(["video-2"], [item["id"] for item in selected["summary"]["experimentSnapshot"]["videos"]])
        self.assertEqual(400, self.request(selection.replace("P014", "P015"))[0])
        self.assertEqual(400, self.request(export + "?trial=" + SESSION + "%3A99")[0])
        self.assertEqual(200, self.request(selection.replace(".json", ".xlsx"))[0])

        path = "/api/sessions/" + SESSION
        auth_cookie = {"Cookie": "gaze_dashboard=" + self.server.browser_token.decode(),
                       "Sec-Fetch-Site": "same-origin"}
        edit = {"action": "edit", "name": "First visit", "notes": "Camera adjusted."}
        self.assertEqual(401, self.request(path + "/manage", "POST", edit, auth=False)[0])
        self.assertEqual(403, self.request(path + "/manage", "POST", edit,
                                           {**auth_cookie, "Sec-Fetch-Site": "cross-site"}, auth=False)[0])
        in_app_headers = {"Cookie": auth_cookie["Cookie"], "Origin": f"http://127.0.0.1:{self.server.server_port}"}
        self.assertEqual(401, self.request(path + "/manage", "POST", edit,
                                           {**in_app_headers, "Origin": "http://localhost:8765"}, auth=False)[0])
        self.assertEqual(200, self.request(path + "/manage", "POST", edit, in_app_headers, auth=False)[0])
        self.assertEqual("First visit", json.loads(self.request(path)[2])["summary"]["research"]["name"])
        self.assertEqual(200, self.request(path + "/manage", "POST", {"action": "delete"}, auth_cookie, auth=False)[0])
        self.assertEqual([], json.loads(self.request("/api/sessions")[2]))
        deleted = json.loads(self.request("/api/sessions?includeDeleted=1")[2])
        self.assertTrue(deleted[0]["research"]["deleted"])
        self.assertEqual(404, self.request(export)[0])
        self.assertEqual(404, self.request(path + "/export.json")[0])
        self.assertEqual({"alreadyUploaded": True}, json.loads(self.request("/api/sessions", "POST", body)[2]))
        self.assertEqual(200, self.request(path + "/manage", "POST", {"action": "restore"}, auth_cookie, auth=False)[0])
        restored = json.loads(self.request(path)[2])
        self.assertEqual("Camera adjusted.", restored["summary"]["research"]["notes"])
        self.assertEqual([1, 2], [trial["trialIndex"] for trial in restored["summary"]["trials"]])


if __name__ == "__main__":
    unittest.main()
