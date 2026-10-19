"""Reusable validation flow and reporting for the GazeFollower launcher."""

from __future__ import annotations

import csv
from collections import Counter, deque
import json
import math
import random
import time
from pathlib import Path

import cv2
import numpy as np
import pygame


# These locations are sampled after calibration.  They deliberately avoid the
# exact 5/9/13-point layouts and measure new frames rather than calibration fit.
VALIDATION_POINTS = (
    (0.12, 0.18), (0.38, 0.18), (0.64, 0.18), (0.88, 0.18),
    (0.20, 0.50), (0.50, 0.50), (0.80, 0.50),
    (0.12, 0.82), (0.36, 0.82), (0.62, 0.82), (0.88, 0.82),
)


def _coordinate(value, index):
    if value is None:
        return None
    try:
        return float(value[index])
    except (IndexError, TypeError, ValueError):
        return None


def _tracking_state_value(gaze_info):
    state = getattr(gaze_info, "tracking_state", None)
    return getattr(state, "value", state)


def calculate_metrics(rows, screen_size):
    """Calculate accuracy, precision and tracking coverage from validation rows."""
    valid = [row for row in rows if row["valid"] and row["error_px"] is not None]
    errors = np.asarray([row["error_px"] for row in valid], dtype=np.float64)
    diagonal = math.hypot(*screen_size)

    point_errors = []
    precision = []
    for point_id in sorted({row["target_id"] for row in valid}):
        samples = [row for row in valid if row["target_id"] == point_id]
        if not samples:
            continue
        point_errors.append(float(np.median([row["error_px"] for row in samples])))
        xy = np.asarray([[row["predicted_x"], row["predicted_y"]] for row in samples])
        if len(xy) > 1:
            centroid = xy.mean(axis=0)
            precision.append(float(np.sqrt(np.mean(np.sum((xy - centroid) ** 2, axis=1)))))

    def value_or_none(function):
        return float(function(errors)) if errors.size else None

    mean_error = value_or_none(np.mean)
    return {
        "total_frames": len(rows),
        "valid_frames": len(valid),
        "valid_rate": (len(valid) / len(rows)) if rows else 0.0,
        "mean_error_px": mean_error,
        "median_error_px": value_or_none(np.median),
        "p90_error_px": (float(np.percentile(errors, 90)) if errors.size else None),
        "rmse_px": (float(np.sqrt(np.mean(errors ** 2))) if errors.size else None),
        "point_median_error_px": (float(np.mean(point_errors)) if point_errors else None),
        "precision_rms_px": (float(np.mean(precision)) if precision else None),
        "mean_error_screen_diagonal_percent": (
            mean_error / diagonal * 100.0 if mean_error is not None and diagonal else None
        ),
    }


def _write_plot(rows, screen_size, output_path):
    width, height = (int(screen_size[0]), int(screen_size[1]))
    scale = min(1.0, 1400.0 / max(width, height))
    canvas_w, canvas_h = max(1, int(width * scale)), max(1, int(height * scale))
    image = np.full((canvas_h, canvas_w, 3), 245, dtype=np.uint8)

    valid = [row for row in rows if row["valid"]]
    for row in valid:
        x = int(np.clip(row["predicted_x"] * scale, 0, canvas_w - 1))
        y = int(np.clip(row["predicted_y"] * scale, 0, canvas_h - 1))
        cv2.circle(image, (x, y), 2, (210, 140, 30), -1)

    for point_id in sorted({row["target_id"] for row in rows}):
        samples = [row for row in valid if row["target_id"] == point_id]
        all_samples = [row for row in rows if row["target_id"] == point_id]
        if not all_samples:
            continue
        tx = int(all_samples[0]["target_x"] * scale)
        ty = int(all_samples[0]["target_y"] * scale)
        cv2.drawMarker(image, (tx, ty), (30, 30, 220), cv2.MARKER_CROSS, 22, 3)
        cv2.putText(image, str(point_id), (tx + 8, ty - 8), cv2.FONT_HERSHEY_SIMPLEX,
                    0.55, (30, 30, 30), 1, cv2.LINE_AA)
        if samples:
            px = int(np.mean([row["predicted_x"] for row in samples]) * scale)
            py = int(np.mean([row["predicted_y"] for row in samples]) * scale)
            cv2.line(image, (tx, ty), (px, py), (80, 80, 80), 1)
            cv2.circle(image, (px, py), 6, (0, 180, 0), -1)

    cv2.imwrite(str(output_path), image)


def save_results(rows, output_dir, metadata, screen_size):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    validation_path = output_dir / "validation.csv"
    fieldnames = [
        "participant_id", "session_id", "target_id", "timestamp",
        "target_x", "target_y", "target_x_normalized", "target_y_normalized",
        "raw_x", "raw_y", "calibrated_x", "calibrated_y",
        "predicted_x", "predicted_y", "error_px", "left_eye_openness",
        "right_eye_openness", "tracking_state", "valid",
    ]
    with validation_path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    metrics = calculate_metrics(rows, screen_size)
    report = {"metadata": metadata, "metrics": metrics}
    (output_dir / "summary.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    _write_plot(rows, screen_size, output_dir / "validation_plot.png")
    return metrics


def _draw_centered_lines(surface, lines, font, color=(245, 245, 245)):
    width, height = surface.get_size()
    rendered = [font.render(line, True, color) for line in lines]
    total_height = sum(item.get_height() + 12 for item in rendered)
    y = (height - total_height) // 2
    for item in rendered:
        surface.blit(item, ((width - item.get_width()) // 2, y))
        y += item.get_height() + 12


def _wait_for_continue(surface, lines):
    font = pygame.font.Font(None, 44)
    clock = pygame.time.Clock()
    while True:
        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                return False
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_ESCAPE:
                    return False
                if event.key == pygame.K_SPACE:
                    return True
        surface.fill((75, 75, 75))
        _draw_centered_lines(surface, lines, font)
        pygame.display.flip()
        clock.tick(30)


def run_validation(gaze_follower, surface, participant_id, session_id, output_dir,
                   metadata, settle_seconds=0.7, sample_seconds=1.5):
    """Run a no-feedback point validation and save analysis-ready artifacts."""
    if not _wait_for_continue(surface, [
        "Validation",
        "Look at each red target without moving your head.",
        "The predicted gaze will be hidden.",
        "Press SPACE to begin or ESC to cancel.",
    ]):
        return None, True

    width, height = surface.get_size()
    points = list(VALIDATION_POINTS)
    random.Random(session_id).shuffle(points)
    rows = []
    aborted = False
    clock = pygame.time.Clock()
    gaze_follower.start_sampling()

    try:
        for point_id, normalized in enumerate(points, start=1):
            target_x = int(normalized[0] * width)
            target_y = int(normalized[1] * height)
            gaze_follower.send_trigger(point_id)
            point_started = time.monotonic()
            collect_started = point_started + settle_seconds
            point_ends = collect_started + sample_seconds
            last_timestamp = None

            while time.monotonic() < point_ends:
                for event in pygame.event.get():
                    if event.type == pygame.QUIT or (
                        event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE
                    ):
                        aborted = True
                if aborted:
                    break

                now = time.monotonic()
                surface.fill((128, 128, 128))
                radius = 18 if now < collect_started else 14
                pygame.draw.circle(surface, (220, 30, 30), (target_x, target_y), radius)
                pygame.draw.circle(surface, (250, 250, 250), (target_x, target_y), 4)
                pygame.display.flip()

                gaze_info = gaze_follower.get_gaze_info()
                timestamp = getattr(gaze_info, "timestamp", None) if gaze_info is not None else None
                if now >= collect_started and gaze_info is not None and timestamp != last_timestamp:
                    last_timestamp = timestamp
                    predicted_x = _coordinate(gaze_info.filtered_gaze_coordinates, 0)
                    predicted_y = _coordinate(gaze_info.filtered_gaze_coordinates, 1)
                    valid = bool(gaze_info.status and predicted_x is not None and predicted_y is not None)
                    error = math.hypot(predicted_x - target_x, predicted_y - target_y) if valid else None
                    rows.append({
                        "participant_id": participant_id,
                        "session_id": session_id,
                        "target_id": point_id,
                        "timestamp": timestamp,
                        "target_x": target_x,
                        "target_y": target_y,
                        "target_x_normalized": normalized[0],
                        "target_y_normalized": normalized[1],
                        "raw_x": _coordinate(gaze_info.raw_gaze_coordinates, 0),
                        "raw_y": _coordinate(gaze_info.raw_gaze_coordinates, 1),
                        "calibrated_x": _coordinate(gaze_info.calibrated_gaze_coordinates, 0),
                        "calibrated_y": _coordinate(gaze_info.calibrated_gaze_coordinates, 1),
                        "predicted_x": predicted_x,
                        "predicted_y": predicted_y,
                        "error_px": error,
                        "left_eye_openness": getattr(gaze_info, "left_openness", None),
                        "right_eye_openness": getattr(gaze_info, "right_openness", None),
                        "tracking_state": _tracking_state_value(gaze_info),
                        "valid": int(valid),
                    })
                clock.tick(60)
            if aborted:
                break
    finally:
        gaze_follower.stop_sampling()
        gaze_follower.save_data(str(Path(output_dir) / "gaze_samples.csv"))

    metadata = dict(metadata)
    metadata["aborted"] = aborted
    metrics = save_results(rows, output_dir, metadata, (width, height))
    mean_text = "N/A" if metrics["mean_error_px"] is None else f'{metrics["mean_error_px"]:.1f} px'
    median_text = "N/A" if metrics["median_error_px"] is None else f'{metrics["median_error_px"]:.1f} px'
    valid_text = f'{metrics["valid_rate"] * 100:.1f}%'
    _wait_for_continue(surface, [
        "Validation complete" if not aborted else "Validation stopped early",
        f"Mean error: {mean_text}",
        f"Median error: {median_text}",
        f"Valid tracking: {valid_text}",
        "Press SPACE or ESC to close.",
    ])
    return metrics, aborted


def _body_region(x, y, body_rect, body_alpha):
    if x is None or y is None or not body_rect.collidepoint(x, y):
        return "Outside body"
    screen_x, screen_y = int(x), int(y)
    if body_alpha[screen_x, screen_y] < 80:
        return "Outside body"
    relative_x = (x - body_rect.left) / body_rect.width
    relative_y = (y - body_rect.top) / body_rect.height
    if relative_y < 0.15:
        return "Head"
    if relative_y < 0.57 and (relative_x < 0.29 or relative_x > 0.71):
        if relative_y < 0.31:
            return "Upper arms"
        if relative_y < 0.48:
            return "Forearms"
        return "Hands"
    if relative_y < 0.34:
        return "Chest"
    if relative_y < 0.47:
        return "Abdomen"
    if relative_y < 0.58:
        return "Pelvis"
    if relative_y < 0.73:
        return "Thighs"
    if relative_y < 0.80:
        return "Knees"
    if relative_y < 0.93:
        return "Lower legs"
    return "Feet"


def _add_heat_sample(heat_grid, x, y, screen_size):
    grid_height, grid_width = heat_grid.shape
    screen_width, screen_height = screen_size
    center_x = int(x / screen_width * grid_width)
    center_y = int(y / screen_height * grid_height)
    sigma = max(3.0, min(grid_width, grid_height) * 0.035)
    radius = int(sigma * 3)
    x0, x1 = max(0, center_x - radius), min(grid_width, center_x + radius + 1)
    y0, y1 = max(0, center_y - radius), min(grid_height, center_y + radius + 1)
    if x0 >= x1 or y0 >= y1:
        return
    yy, xx = np.mgrid[y0:y1, x0:x1]
    heat_grid[y0:y1, x0:x1] += np.exp(
        -((xx - center_x) ** 2 + (yy - center_y) ** 2) / (2.0 * sigma ** 2)
    )


def _render_heatmap(heat_grid, body_mask, screen_size):
    nonzero = heat_grid[heat_grid > 0]
    if nonzero.size == 0:
        return pygame.Surface(screen_size, pygame.SRCALPHA)
    scale = max(float(np.percentile(nonzero, 98)), 1.0)
    normalized = np.clip(heat_grid / scale, 0.0, 1.0)
    colors_bgr = cv2.applyColorMap((normalized * 255).astype(np.uint8), cv2.COLORMAP_TURBO)
    colors_rgb = cv2.cvtColor(colors_bgr, cv2.COLOR_BGR2RGB)
    alpha = (np.power(normalized, 0.55) * 210.0 * body_mask).astype(np.uint8)
    rgba = np.dstack((colors_rgb, alpha))
    small = pygame.image.frombuffer(
        rgba.tobytes(), (heat_grid.shape[1], heat_grid.shape[0]), "RGBA"
    ).convert_alpha()
    return pygame.transform.smoothscale(small, screen_size)


def run_live_test(gaze_follower, surface, participant_id, session_id, output_dir, metadata):
    """Display a cumulative gaze heatmap over a human body illustration."""
    if not _wait_for_continue(surface, [
        "Human-body focus test",
        "Look naturally at different regions of the body model.",
        "The heatmap shows accumulated predicted visual focus.",
        "Press SPACE to begin or ESC to cancel.",
    ]):
        return None, True

    width, height = surface.get_size()
    output_dir = Path(output_dir)
    rows = []
    trail = deque(maxlen=12)
    clock = pygame.time.Clock()
    font = pygame.font.Font(None, 30)
    status_font = pygame.font.Font(None, 34)

    body_path = Path(__file__).resolve().parent.parent / "gazefollower" / "res" / "image" / "human_body_front.png"
    body_source = pygame.image.load(str(body_path)).convert_alpha()
    content_top = 92
    content_height = max(1, height - content_top - 18)
    body_scale = min(
        (width * 0.58) / body_source.get_width(),
        content_height / body_source.get_height()
    )
    body_size = (
        max(1, int(body_source.get_width() * body_scale)),
        max(1, int(body_source.get_height() * body_scale)),
    )
    body_image = pygame.transform.smoothscale(body_source, body_size)
    body_rect = body_image.get_rect(center=(width // 2, content_top + content_height // 2))
    body_layer = pygame.Surface((width, height), pygame.SRCALPHA)
    body_layer.blit(body_image, body_rect)
    body_alpha = pygame.surfarray.array_alpha(body_layer)

    heat_scale = 8
    heat_width = max(1, int(math.ceil(width / heat_scale)))
    heat_height = max(1, int(math.ceil(height / heat_scale)))
    heat_grid = np.zeros((heat_height, heat_width), dtype=np.float32)
    body_mask = cv2.resize(
        body_alpha.T.astype(np.float32) / 255.0,
        (heat_width, heat_height), interpolation=cv2.INTER_AREA
    )
    heatmap_surface = pygame.Surface((width, height), pygame.SRCALPHA)
    last_heat_render = 0.0
    last_timestamp = None
    current_region = "Not tracking"
    aborted = False
    finished = False
    started = time.monotonic()
    gaze_follower.start_sampling()

    try:
        while not finished:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    aborted = True
                    finished = True
                elif event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
                    finished = True
                elif event.type == pygame.KEYDOWN and event.key == pygame.K_c:
                    trail.clear()
                    heat_grid.fill(0)
                    heatmap_surface.fill((0, 0, 0, 0))
            if finished:
                continue

            gaze_info = gaze_follower.get_gaze_info()
            timestamp = getattr(gaze_info, "timestamp", None) if gaze_info is not None else None
            predicted_x = _coordinate(getattr(gaze_info, "filtered_gaze_coordinates", None), 0)
            predicted_y = _coordinate(getattr(gaze_info, "filtered_gaze_coordinates", None), 1)
            valid = bool(
                gaze_info is not None and gaze_info.status and
                predicted_x is not None and predicted_y is not None
            )
            inside = bool(valid and 0 <= predicted_x < width and 0 <= predicted_y < height)
            body_region = _body_region(predicted_x, predicted_y, body_rect, body_alpha) if inside else "Outside body"

            if gaze_info is not None and timestamp != last_timestamp:
                last_timestamp = timestamp
                if inside:
                    _add_heat_sample(heat_grid, predicted_x, predicted_y, (width, height))
                    trail.append((int(predicted_x), int(predicted_y)))
                current_region = body_region if valid else "Not tracking"
                rows.append({
                    "participant_id": participant_id,
                    "session_id": session_id,
                    "timestamp": timestamp,
                    "calibrated_x": _coordinate(gaze_info.calibrated_gaze_coordinates, 0),
                    "calibrated_y": _coordinate(gaze_info.calibrated_gaze_coordinates, 1),
                    "predicted_x": predicted_x,
                    "predicted_y": predicted_y,
                    "body_region": current_region,
                    "left_eye_openness": getattr(gaze_info, "left_openness", None),
                    "right_eye_openness": getattr(gaze_info, "right_openness", None),
                    "tracking_state": _tracking_state_value(gaze_info),
                    "valid": int(valid),
                })

            now = time.monotonic()
            if now - last_heat_render >= 0.10:
                heatmap_surface = _render_heatmap(heat_grid, body_mask, (width, height))
                last_heat_render = now

            surface.fill((18, 22, 29))
            surface.blit(body_layer, (0, 0))
            surface.blit(heatmap_surface, (0, 0))
            for index, point in enumerate(trail):
                shade = int(100 + 130 * (index + 1) / len(trail))
                pygame.draw.circle(surface, (245, shade, 60), point, 2)

            status_color = (125, 235, 150) if valid else (240, 110, 110)
            if valid:
                marker_x = int(np.clip(predicted_x, 0, width - 1))
                marker_y = int(np.clip(predicted_y, 0, height - 1))
                pygame.draw.circle(surface, (255, 255, 255), (marker_x, marker_y), 12, 2)
                pygame.draw.circle(surface, (255, 245, 210), (marker_x, marker_y), 3)
                if not inside:
                    current_region = "Outside screen"

            instruction = font.render("BODY FOCUS HEATMAP   C: clear   ESC: finish", True, (225, 225, 225))
            status = status_font.render(f"Current focus: {current_region}", True, status_color)
            surface.blit(instruction, (22, 18))
            surface.blit(status, (22, 50))
            pygame.display.flip()
            clock.tick(60)
    finally:
        pygame.image.save(surface, str(output_dir / "live_heatmap.png"))
        gaze_follower.stop_sampling()
        gaze_follower.save_data(str(output_dir / "gaze_samples.csv"))

    fieldnames = [
        "participant_id", "session_id", "timestamp", "calibrated_x", "calibrated_y",
        "predicted_x", "predicted_y", "body_region", "left_eye_openness",
        "right_eye_openness", "tracking_state", "valid",
    ]
    with (output_dir / "live_predictions.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    duration = max(time.monotonic() - started, 0.0)
    valid_count = sum(row["valid"] for row in rows)
    region_counts = Counter(
        row["body_region"] for row in rows
        if row["valid"] and row["body_region"] not in ("Outside body", "Outside screen")
    )
    stats = {
        "total_frames": len(rows),
        "valid_frames": valid_count,
        "valid_rate": valid_count / len(rows) if rows else 0.0,
        "duration_seconds": duration,
        "average_fps": len(rows) / duration if duration else 0.0,
        "dominant_body_region": region_counts.most_common(1)[0][0] if region_counts else None,
        "body_region_frame_counts": dict(region_counts),
    }
    live_metadata = dict(metadata)
    live_metadata.update({
        "aborted": aborted,
        "feedback_visible": True,
        "display_type": "human_body_heatmap",
        "body_asset": str(body_path),
    })
    (output_dir / "summary.json").write_text(
        json.dumps({"metadata": live_metadata, "live_statistics": stats}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return stats, aborted
