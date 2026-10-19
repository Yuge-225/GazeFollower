"""Simple experiment launcher for calibrated screen-gaze validation."""

from __future__ import annotations

import datetime as dt
import os
import re
import sys
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import numpy as np
import pygame
from screeninfo import get_monitors

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

import gazefollower
from example.experiment_runner import VALIDATION_POINTS, run_live_test, run_validation
from gazefollower import GazeFollower
from gazefollower.calibration import MultivariateRidgeCalibration, SVRCalibration
from gazefollower.face_alignment import BlazeFaceAlignment, MediaPipeFaceAlignment
from gazefollower.gaze_estimator import MGazeNetGazeEstimator
from gazefollower.misc import CalibrationMode, DefaultConfig


class LauncherGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("GazeFollower Experiment")
        self.root.geometry("820x760")
        self.root.minsize(760, 700)
        self.monitors = self._load_monitors()

        self.participant_var = tk.StringVar(value="P01")
        self.mode_var = tk.StringVar(value="Validation")
        self.output_var = tk.StringVar(value=str(REPO_ROOT / "results"))
        self.model_path_var = tk.StringVar(value="base.mnn")
        self.face_align_var = tk.StringVar(value="MediaPipe")
        self.calib_var = tk.StringVar(value="SVR")
        self.cali_mode_var = tk.StringVar(value="13")
        self.use_mp_var = tk.BooleanVar(value=False)
        self.enable_face_filter_var = tk.BooleanVar(value=True)
        self.monitor_var = tk.StringVar(value=self._monitor_labels()[0])

        self._create_widgets()

    @staticmethod
    def _load_monitors():
        try:
            monitors = get_monitors()
            if monitors:
                return monitors
        except Exception:
            pass

        class FallbackMonitor:
            width, height, x, y, name = 1920, 1080, 0, 0, "Default"

        return [FallbackMonitor()]

    def _monitor_labels(self):
        labels = []
        for index, monitor in enumerate(self.monitors):
            name = getattr(monitor, "name", None) or f"Display {index + 1}"
            labels.append(f"{index + 1}: {name} ({monitor.width} x {monitor.height})")
        return labels

    @staticmethod
    def _section(parent, title):
        frame = ttk.LabelFrame(parent, text=title, padding=10)
        frame.pack(fill=tk.X, pady=4)
        return frame

    def _create_widgets(self):
        main = ttk.Frame(self.root, padding=18)
        main.pack(fill=tk.BOTH, expand=True)
        ttk.Label(main, text="GazeFollower Validation Session",
                  font=("Segoe UI", 15, "bold")).pack(pady=(0, 10))

        identity = self._section(main, "1. Session")
        ttk.Label(identity, text="Participant ID:").grid(row=0, column=0, sticky="w", padx=5)
        ttk.Entry(identity, textvariable=self.participant_var, width=18).grid(row=0, column=1, sticky="w", padx=5)
        ttk.Label(identity, text="Session ID is generated automatically.", foreground="#666666").grid(
            row=0, column=2, sticky="w", padx=10)
        ttk.Label(identity, text="Results folder:").grid(row=1, column=0, sticky="w", padx=5, pady=(8, 0))
        ttk.Entry(identity, textvariable=self.output_var, width=55).grid(
            row=1, column=1, columnspan=2, sticky="ew", padx=5, pady=(8, 0))
        ttk.Button(identity, text="Browse...", command=self._browse_output).grid(
            row=1, column=3, padx=5, pady=(8, 0))
        identity.columnconfigure(2, weight=1)

        mode = self._section(main, "2. Mode")
        ttk.Radiobutton(mode, text="Validation (measure accuracy)", variable=self.mode_var,
                        value="Validation").pack(side=tk.LEFT, padx=8)
        ttk.Radiobutton(mode, text="Live Test (human-body heatmap)", variable=self.mode_var,
                        value="Live Test").pack(side=tk.LEFT, padx=18)

        display = self._section(main, "3. Display")
        ttk.Label(display, text="Experiment display:").pack(side=tk.LEFT, padx=5)
        ttk.Combobox(display, textvariable=self.monitor_var, values=self._monitor_labels(),
                     state="readonly", width=48).pack(side=tk.LEFT, padx=5)

        model = self._section(main, "4. Gaze Model")
        ttk.Combobox(model, textvariable=self.model_path_var, values=["base.mnn"],
                     state="readonly", width=35).pack(side=tk.LEFT, padx=5)
        ttk.Button(model, text="Browse for .mnn...", command=self._browse_model).pack(side=tk.LEFT, padx=5)

        alignment = self._section(main, "5. Face Alignment")
        ttk.Radiobutton(alignment, text="MediaPipe (accuracy)", variable=self.face_align_var,
                        value="MediaPipe").pack(side=tk.LEFT, padx=8)
        ttk.Radiobutton(alignment, text="BlazeFace (speed)", variable=self.face_align_var,
                        value="BlazeFace").pack(side=tk.LEFT, padx=8)
        ttk.Checkbutton(alignment, text="Smooth face/eye boxes with 1-Euro filter",
                        variable=self.enable_face_filter_var).pack(side=tk.LEFT, padx=12)

        calibration = self._section(main, "6. Personal Calibration")
        ttk.Radiobutton(calibration, text="SVR", variable=self.calib_var,
                        value="SVR").pack(side=tk.LEFT, padx=8)
        ttk.Radiobutton(calibration, text="Multivariate Ridge", variable=self.calib_var,
                        value="MultivariateRidge").pack(side=tk.LEFT, padx=8)
        ttk.Label(calibration, text="Points:").pack(side=tk.LEFT, padx=(20, 4))
        ttk.Combobox(calibration, textvariable=self.cali_mode_var,
                     values=["5", "9", "13"], state="readonly", width=5).pack(side=tk.LEFT)

        processing = self._section(main, "7. Processing")
        ttk.Checkbutton(
            processing,
            text="Enable multiprocessing (optional; single-process is easier to diagnose)",
            variable=self.use_mp_var,
        ).pack(side=tk.LEFT, padx=5)

        note = (
            "Both modes run camera preview and personal calibration. Validation hides predictions "
            "and calculates error; Live Test accumulates predicted focus over a human-body heatmap."
        )
        ttk.Label(main, text=note, wraplength=760, foreground="#444444").pack(
            fill=tk.X, pady=(10, 4))

        buttons = ttk.Frame(main)
        buttons.pack(fill=tk.X, pady=(10, 0))
        ttk.Button(buttons, text="Start Experiment", command=self.launch,
                   style="Accent.TButton").pack(side=tk.RIGHT)

    def _browse_model(self):
        path = filedialog.askopenfilename(filetypes=[("MNN model", "*.mnn")])
        if path:
            self.model_path_var.set(path)

    def _browse_output(self):
        path = filedialog.askdirectory(initialdir=self.output_var.get())
        if path:
            self.output_var.set(path)

    def _selected_monitor(self):
        try:
            index = self._monitor_labels().index(self.monitor_var.get())
        except ValueError:
            index = 0
        return index, self.monitors[index]

    def _resolved_model_path(self):
        value = self.model_path_var.get().strip()
        if value == "base.mnn":
            return Path(gazefollower.__file__).parent / "res" / "model_weights" / value
        return Path(value).expanduser().resolve()

    def _validate_inputs(self):
        participant = self.participant_var.get().strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]+", participant):
            raise ValueError("Participant ID may contain only letters, numbers, '-' and '_'.")
        model_path = self._resolved_model_path()
        if not model_path.is_file():
            raise FileNotFoundError(f"Model file not found: {model_path}")
        output_root = Path(self.output_var.get()).expanduser().resolve()
        output_root.mkdir(parents=True, exist_ok=True)
        return participant, model_path, output_root

    def launch(self):
        try:
            participant, model_path, output_root = self._validate_inputs()
        except Exception as exc:
            messagebox.showerror("Invalid configuration", str(exc), parent=self.root)
            return

        session_id = dt.datetime.now().strftime("S%Y%m%d_%H%M%S_%f")
        output_dir = output_root / participant / session_id
        output_dir.mkdir(parents=True, exist_ok=False)
        calibration_dir = output_dir / "calibration_model"
        monitor_index, monitor = self._selected_monitor()
        screen_size = np.array([monitor.width, monitor.height], dtype=int)
        use_mp = bool(self.use_mp_var.get())

        config = DefaultConfig()
        config.screen_size = screen_size
        config.use_multiprocessing = use_mp
        config.enable_face_filter = bool(self.enable_face_filter_var.get())
        config.cali_mode = {
            "5": CalibrationMode.FIVE_POINT,
            "9": CalibrationMode.NINE_POINT,
            "13": CalibrationMode.THIRTEEN_POINT,
        }[self.cali_mode_var.get()]

        try:
            if self.calib_var.get() == "SVR":
                calibration = SVRCalibration(model_save_path=str(calibration_dir))
            else:
                calibration = MultivariateRidgeCalibration(
                    alpha=0.1, model_save_path=str(calibration_dir / "ridge.npz")
                )

            if self.face_align_var.get() == "MediaPipe":
                face_alignment = MediaPipeFaceAlignment(enable_filter=config.enable_face_filter)
            else:
                face_alignment = BlazeFaceAlignment(enable_filter=config.enable_face_filter)
        except Exception as exc:
            messagebox.showerror("Initialization failed", str(exc), parent=self.root)
            return

        metadata = {
            "participant_id": participant,
            "session_id": session_id,
            "created_at": dt.datetime.now().astimezone().isoformat(),
            "model_path": str(model_path),
            "mode": self.mode_var.get(),
            "face_alignment": self.face_align_var.get(),
            "face_box_filter_enabled": config.enable_face_filter,
            "calibration_algorithm": self.calib_var.get(),
            "calibration_points": int(self.cali_mode_var.get()),
            "multiprocessing": use_mp,
            "monitor_index": monitor_index,
            "monitor_name": getattr(monitor, "name", None),
            "screen_width": int(screen_size[0]),
            "screen_height": int(screen_size[1]),
            "validation_point_count": len(VALIDATION_POINTS),
            "validation_settle_seconds": 0.7,
            "validation_sample_seconds": 1.5,
        }

        gaze_follower = None
        metrics = None
        error_message = None
        self.root.withdraw()
        try:
            os.environ["SDL_VIDEO_FULLSCREEN_DISPLAY"] = str(monitor_index)
            pygame.init()
            surface = pygame.display.set_mode(
                tuple(screen_size), pygame.FULLSCREEN, display=monitor_index
            )
            pygame.display.set_caption("GazeFollower Validation")

            gaze_follower = GazeFollower(
                face_alignment=face_alignment,
                gaze_estimator=MGazeNetGazeEstimator(model_path=str(model_path)),
                calibration=calibration,
                config=config,
                use_multiprocessing=use_mp,
            )
            gaze_follower.preview(win=surface)
            gaze_follower.calibrate(win=surface)
            metadata["calibration_model_saved"] = bool(calibration.save_model())
            if self.mode_var.get() == "Live Test":
                metrics, _ = run_live_test(
                    gaze_follower, surface, participant, session_id, output_dir, metadata
                )
            else:
                metrics, _ = run_validation(
                    gaze_follower, surface, participant, session_id, output_dir, metadata
                )
        except Exception as exc:
            error_message = f"{exc}\n\nPartial output: {output_dir}"
        finally:
            if gaze_follower is not None:
                try:
                    gaze_follower.release()
                except Exception:
                    pass
            pygame.quit()
            self.root.deiconify()

        if error_message is not None:
            messagebox.showerror("Experiment failed", error_message, parent=self.root)
        elif metrics is not None:
            if self.mode_var.get() == "Live Test":
                dominant_region = metrics.get("dominant_body_region") or "N/A"
                result_text = (
                    f"Duration: {metrics['duration_seconds']:.1f} seconds\n"
                    f"Valid tracking: {metrics['valid_rate'] * 100:.1f}%\n"
                    f"Dominant body region: {dominant_region}"
                )
            else:
                mean = metrics["mean_error_px"]
                mean_text = "N/A" if mean is None else f"{mean:.1f} px"
                result_text = (
                    f"Mean error: {mean_text}\n"
                    f"Valid tracking: {metrics['valid_rate'] * 100:.1f}%"
                )
            messagebox.showinfo(
                "Session saved", f"{result_text}\n\nResults: {output_dir}", parent=self.root
            )


if __name__ == "__main__":
    app_root = tk.Tk()
    style = ttk.Style()
    style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"))
    LauncherGUI(app_root)
    app_root.mainloop()
