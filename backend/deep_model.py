from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any


MODEL_VERSION = "tiny-mlp-risk-tier-v4-baseline-step"

DOWN_OBSTACLE_CM = 20.0
FRONT_WARN_CM = 70.0
FRONT_DANGER_CM = 35.0


@dataclass(frozen=True)
class DeepRiskFeatures:
    front_close: float
    side_blocked: float
    ground_drop: float
    history_density: float
    history_high_ratio: float
    history_level: float
    location_uncertainty: float


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def sigmoid(value: float) -> float:
    if value >= 0:
        z = math.exp(-value)
        return 1.0 / (1.0 + z)
    z = math.exp(value)
    return z / (1.0 + z)


def relu(value: float) -> float:
    return value if value > 0.0 else 0.0


def cm(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    if parsed <= 0:
        return default
    return parsed


def level_rank(level: str | None) -> float:
    if level == "high":
        return 1.0
    if level == "medium":
        return 0.55
    return 0.0


def quality_uncertainty(quality: str | None, accuracy_m: float | None) -> float:
    quality_rank = {
        "good": 0.0,
        "usable": 0.25,
        "poor": 0.65,
        "stale": 0.85,
        "mock": 0.45,
    }.get((quality or "").lower(), 0.35)

    if accuracy_m is None:
        return quality_rank
    return max(quality_rank, clamp((float(accuracy_m) - 5.0) / 60.0))


def make_features(req: Any, history: dict[str, Any]) -> DeepRiskFeatures:
    front = cm(getattr(req, "front_cm", None), 220.0)
    left = cm(getattr(req, "left_cm", None), 180.0)
    right = cm(getattr(req, "right_cm", None), 180.0)

    risk_count = max(0, int(history.get("risk_count", 0)))
    high_count = max(0, int(history.get("high_count", 0)))
    medium_count = max(0, int(history.get("medium_count", 0)))

    best_side = max(left, right)
    front_close = clamp((FRONT_WARN_CM - front) / max(1.0, FRONT_WARN_CM - FRONT_DANGER_CM))
    side_blocked = clamp((95.0 - best_side) / 75.0)
    reported_risk = str(
        getattr(req, "risk_type", None)
        or getattr(req, "manual_risk_type", None)
        or getattr(req, "alert_type", None)
        or ""
    )
    # The posture-aware firmware state machine owns step detection. Absolute
    # down-facing distance cannot distinguish cane angle from a ground delta.
    ground_drop = 1.0 if reported_risk in {"ground_step_down", "ground_step_up", "ground_drop", "ground_step"} else 0.0
    history_density = clamp(risk_count / 6.0)
    history_high_ratio = clamp((high_count + 0.4 * medium_count) / max(1.0, float(risk_count)))
    history_level = level_rank(str(history.get("max_level", "low")))
    location_uncertainty = quality_uncertainty(
        getattr(req, "location_quality", None),
        getattr(req, "accuracy_m", None),
    )

    return DeepRiskFeatures(
        front_close=front_close,
        side_blocked=side_blocked,
        ground_drop=ground_drop,
        history_density=history_density,
        history_high_ratio=history_high_ratio,
        history_level=history_level,
        location_uncertainty=location_uncertainty,
    )


def tiny_mlp(features: DeepRiskFeatures) -> float:
    x = [
        features.front_close,
        features.side_blocked,
        features.ground_drop,
        features.history_density,
        features.history_high_ratio,
        features.history_level,
        features.location_uncertainty,
    ]

    hidden1_weights = [
        [1.55, 0.35, 0.30, 0.20, 0.15, 0.15, 0.05],
        [0.20, 0.45, 2.15, 0.10, 0.20, 0.20, 0.05],
        [0.35, 0.25, 0.10, 0.90, 1.15, 0.85, 0.20],
        [0.15, 1.35, 0.20, 0.20, 0.25, 0.20, 0.20],
        [0.20, 0.20, 0.10, 0.15, 0.25, 0.20, 0.95],
        [0.75, 0.55, 0.85, 0.45, 0.60, 0.40, 0.15],
    ]
    hidden1_bias = [-0.55, -0.45, -0.50, -0.35, -0.40, -0.60]

    h1 = []
    for weights, bias in zip(hidden1_weights, hidden1_bias):
        h1.append(relu(sum(w * v for w, v in zip(weights, x)) + bias))

    hidden2_weights = [
        [1.20, 0.20, 0.35, 0.25, 0.10, 0.55],
        [0.25, 1.25, 0.30, 0.20, 0.10, 0.55],
        [0.30, 0.25, 1.10, 0.20, 0.15, 0.40],
        [0.15, 0.20, 0.25, 0.95, 0.65, 0.30],
    ]
    hidden2_bias = [-0.25, -0.25, -0.25, -0.20]

    h2 = []
    for weights, bias in zip(hidden2_weights, hidden2_bias):
        h2.append(relu(sum(w * v for w, v in zip(weights, h1)) + bias))

    output = 1.25 * h2[0] + 1.35 * h2[1] + 0.95 * h2[2] + 0.65 * h2[3] - 1.05
    return sigmoid(output)


def safety_floor(req: Any, history: dict[str, Any]) -> float:
    front = cm(getattr(req, "front_cm", None), 220.0)
    down = cm(getattr(req, "down_cm", None), 30.0)

    floor = 0.0
    if down < DOWN_OBSTACLE_CM:
        floor = max(floor, 0.18)
    if front < FRONT_DANGER_CM:
        floor = max(floor, 0.74)
    elif front < FRONT_WARN_CM:
        floor = max(floor, 0.18)
    if history.get("high_count", 0) >= 2:
        floor = max(floor, 0.24)
    return floor


def level_from_score(score: float) -> str:
    if score >= 0.38:
        return "medium"
    return "low"


def level_for_request(req: Any, score: float) -> str:
    risk_type = str(
        getattr(req, "risk_type", None)
        or getattr(req, "manual_risk_type", None)
        or getattr(req, "alert_type", None)
        or ""
    )
    if risk_type in {"sos", "fall_detected"} or getattr(req, "fall_detected", False):
        return "high"
    if risk_type in {"ground_step_down", "ground_step_up", "ground_drop", "ground_step", "user_mark"}:
        return "medium"
    front = cm(getattr(req, "front_cm", None), 220.0)
    if risk_type in {
        "front_obstacle",
        "left_obstacle",
        "right_obstacle",
        "down_obstacle",
        "history_risk",
        "prolonged_obstacle",
        "approaching_obstacle",
        "voice_request",
    }:
        return "low"
    return level_from_score(score)


def confidence_from_score(score: float) -> float:
    return round(0.55 + abs(score - 0.5) * 0.9, 3)


def score_deep_risk(req: Any, history: dict[str, Any]) -> dict[str, Any]:
    features = make_features(req, history)
    mlp_score = tiny_mlp(features)
    floor_score = safety_floor(req, history)
    score = max(mlp_score, floor_score)

    feature_dict = {
        "front_close": round(features.front_close, 3),
        "side_blocked": round(features.side_blocked, 3),
        "ground_drop": round(features.ground_drop, 3),
        "history_density": round(features.history_density, 3),
        "history_high_ratio": round(features.history_high_ratio, 3),
        "history_level": round(features.history_level, 3),
        "location_uncertainty": round(features.location_uncertainty, 3),
    }

    return {
        "model": MODEL_VERSION,
        "score": round(score, 3),
        "mlp_score": round(mlp_score, 3),
        "safety_floor": round(floor_score, 3),
        "level": level_for_request(req, score),
        "confidence": confidence_from_score(score),
        "features": feature_dict,
    }
