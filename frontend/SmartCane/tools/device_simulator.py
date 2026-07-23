#!/usr/bin/env python3
"""SmartCane telemetry simulator.

Example:
    python tools/device_simulator.py --server http://118.31.221.165:8016 --device-id cane_001 --interval 3
"""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def timestamp_now() -> str:
    return datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds")


def distances_for_step(step: int) -> tuple[int, int, int, str]:
    scenario = step % 6
    if scenario == 0:
        return 1800, 1600, 1700, "normal"
    if scenario == 1:
        return 900, 1500, 1700, "front-medium"
    if scenario == 2:
        return 420, 1500, 1700, "front-high"
    if scenario == 3:
        return 1600, 360, 1700, "left-high"
    if scenario == 4:
        return 1600, 1500, 380, "right-high"
    return 1500, 1300, 1400, "normal"


def build_payload(device_id: str, step: int) -> dict[str, object]:
    front, left, right, scenario = distances_for_step(step)
    return {
        "deviceId": device_id,
        "frontDistanceMm": front,
        "leftDistanceMm": left,
        "rightDistanceMm": right,
        "latitude": 39.9042 + (step % 10) * 0.00001,
        "longitude": 116.4074 + (step % 10) * 0.00001,
        "timestamp": timestamp_now(),
        "scenario": scenario,
    }


def post_telemetry(server: str, payload: dict[str, object]) -> tuple[int, str]:
    url = server.rstrip("/") + "/telemetry"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
        },
    )
    with urlopen(request, timeout=10) as response:
        return response.status, response.read().decode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="SmartCane telemetry simulator")
    parser.add_argument("--server", default="http://118.31.221.165:8016", help="SmartCane server base URL")
    parser.add_argument("--device-id", default="cane_001", help="device id to report")
    parser.add_argument("--interval", type=float, default=3.0, help="report interval in seconds")
    args = parser.parse_args()

    step = 0
    print(
        f"Starting simulator: server={args.server} deviceId={args.device_id} interval={args.interval}s"
    )
    print("Press Ctrl+C to stop.")

    try:
        while True:
            payload = build_payload(args.device_id, step)
            print("\nPOST /telemetry payload:")
            print(json.dumps(payload, ensure_ascii=False, indent=2))
            try:
                status, response_body = post_telemetry(args.server, payload)
                print(f"response status={status} body={response_body}")
            except HTTPError as error:
                print(f"response status={error.code} body={error.read().decode('utf-8', errors='replace')}")
            except URLError as error:
                print(f"request failed: {error}")

            step += 1
            time.sleep(max(args.interval, 0.1))
    except KeyboardInterrupt:
        print("\nSimulator stopped.")


if __name__ == "__main__":
    main()
