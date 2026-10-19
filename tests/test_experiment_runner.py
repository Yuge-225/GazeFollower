import json
import tempfile
import unittest
from pathlib import Path

from example.experiment_runner import calculate_metrics, save_results


def sample_row(target_id, target_x, target_y, offset, valid=1):
    return {
        "participant_id": "P01", "session_id": "S_test", "target_id": target_id,
        "timestamp": target_id * 100 + offset, "target_x": target_x, "target_y": target_y,
        "target_x_normalized": target_x / 800, "target_y_normalized": target_y / 600,
        "raw_x": 0.5, "raw_y": 0.5, "calibrated_x": target_x + offset,
        "calibrated_y": target_y, "predicted_x": target_x + offset,
        "predicted_y": target_y, "error_px": float(abs(offset)) if valid else None,
        "left_eye_openness": 1.0, "right_eye_openness": 1.0,
        "tracking_state": 0, "valid": valid,
    }


class TestExperimentRunner(unittest.TestCase):
    def setUp(self):
        self.rows = [
            sample_row(point, x, y, offset)
            for point, (x, y) in enumerate(((100, 100), (500, 300)), 1)
            for offset in range(4)
        ]
        self.rows.append(sample_row(2, 500, 300, 0, valid=0))

    def test_metrics(self):
        metrics = calculate_metrics(self.rows, (800, 600))
        self.assertEqual(metrics["total_frames"], 9)
        self.assertEqual(metrics["valid_frames"], 8)
        self.assertAlmostEqual(metrics["mean_error_px"], 1.5)
        self.assertAlmostEqual(metrics["median_error_px"], 1.5)

    def test_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            metrics = save_results(self.rows, directory, {"participant_id": "P01"}, (800, 600))
            output = Path(directory)
            self.assertTrue((output / "validation.csv").is_file())
            self.assertTrue((output / "validation_plot.png").is_file())
            report = json.loads((output / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(report["metrics"]["valid_frames"], metrics["valid_frames"])


if __name__ == "__main__":
    unittest.main()
