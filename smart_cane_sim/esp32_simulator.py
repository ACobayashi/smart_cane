from __future__ import annotations

import json
import random
import time
import urllib.request
from datetime import datetime, timezone


BASE_URL = "http://127.0.0.1:8000"
DEVICE_ID = "cane_001"


SENSORS = [
    ("obstacle_detected", "front_obstacle", "front", "tof_front"),
    ("obstacle_detected", "left_obstacle", "left", "tof_left"),
    ("obstacle_detected", "right_obstacle", "right", "tof_right"),
    ("ground_drop_detected", "ground_drop", "down", "tof_down"),
]


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def post_json(path: str, payload: dict) -> dict:
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode("utf-8"))


def make_event() -> dict:
    event_type, risk_type, direction, sensor = random.choice(SENSORS)
    front_mm = random.randint(900, 1600)
    left_mm = random.randint(800, 1500)
    right_mm = random.randint(800, 1500)
    down_mm = random.randint(600, 760)
    ground_base_mm = 650

    if risk_type == "ground_drop":
        down_mm = random.randint(900, 1400)
        distance = down_mm
        level = "high" if down_mm - ground_base_mm > 350 else "medium"
    elif risk_type == "front_obstacle":
        front_mm = random.randint(250, 1100)
        distance = front_mm
        level = "high" if front_mm < 550 else "medium"
    elif risk_type == "left_obstacle":
        left_mm = random.randint(250, 900)
        distance = left_mm
        level = "high" if left_mm < 500 else "medium"
    elif risk_type == "right_obstacle":
        right_mm = random.randint(250, 900)
        distance = right_mm
        level = "high" if right_mm < 500 else "medium"
    else:
        distance = random.randint(250, 1300)
        level = "high" if distance < 550 else "medium" if distance < 1000 else "low"

    return {
        "device_id": DEVICE_ID,
        "event_type": event_type,
        "risk_type": risk_type,
        "level": level,
        "direction": direction,
        "sensor": sensor,
        "distance_mm": distance,
        "front_mm": front_mm,
        "left_mm": left_mm,
        "right_mm": right_mm,
        "down_mm": down_mm,
        "ground_base_mm": ground_base_mm,
        "alarm_triggered": level in {"medium", "high"},
        "alarm_mode": "vibration" if level == "medium" else "vibration_buzzer",
        "battery": random.randint(65, 100),
        "timestamp": now_iso(),
    }


def seed_location() -> None:
    payload = {
        "device_id": DEVICE_ID,
        "lat": 31.2304 + random.uniform(-0.0008, 0.0008),
        "lng": 121.4737 + random.uniform(-0.0008, 0.0008),
        "accuracy_m": 15,
        "source": "simulator",
        "timestamp": now_iso(),
    }
    post_json("/api/locations", payload)


def main() -> None:
    print(f"ESP32-C5 simulator posting to {BASE_URL}")
    print("Press Ctrl+C to stop.")
    seed_location()
    while True:
        event = make_event()
        saved = post_json("/api/risk-events", event)
        print(
            f"#{saved['id']} {saved['level']} {saved['risk_type']} "
            f"{saved['distance_mm']}mm -> {saved['ai_message']}"
        )
        time.sleep(2)


if __name__ == "__main__":
    main()
