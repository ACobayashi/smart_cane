from __future__ import annotations

import json
import random
import time
import urllib.request
from datetime import datetime, timezone


BASE_URL = "http://127.0.0.1:8000"
DEVICE_ID = "cane_001"


SENSORS = [
    ("front_obstacle", "front", "tof_front"),
    ("left_obstacle", "left", "tof_left"),
    ("right_obstacle", "right", "tof_right"),
    ("ground_drop", "down", "tof_down"),
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
    risk_type, direction, sensor = random.choice(SENSORS)
    if risk_type == "ground_drop":
        distance = random.randint(850, 1400)
        level = "high" if distance > 1050 else "medium"
    else:
        distance = random.randint(250, 1300)
        level = "high" if distance < 550 else "medium" if distance < 1000 else "low"
    return {
        "device_id": DEVICE_ID,
        "risk_type": risk_type,
        "level": level,
        "direction": direction,
        "sensor": sensor,
        "distance_mm": distance,
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
