# Smart Cane API Contract

This document is the shared contract for team members A, B, and C.

- A: ESP32-C5 Arduino firmware, sensors, local risk fusion, route upload.
- B: FastAPI backend, SQLite, AI advice service.
- C: map frontend and user-facing display.

The firmware uses Arduino IDE / Arduino framework and builds from `firmware/smartcane_arduino/smartcane_arduino.ino`.

## Units

| Field | Unit |
| --- | --- |
| `front_cm`, `left_cm`, `right_cm`, `down_cm` | centimeters |
| `distance_mm` | millimeters |
| `lat`, `lng` | WGS84 decimal degrees |
| `battery` | percentage, `-1` means unknown |
| `radius` | meters |
| `accuracy_m` | meters |
| `hdop` | NMEA horizontal dilution of precision |

## Risk Levels

Allowed values:

- `low`
- `medium`
- `high`

The backend accepts both `level` and `risk_level`. Firmware sends both for compatibility.

## Risk Types

Recommended values:

- `front_obstacle`
- `left_obstacle`
- `right_obstacle`
- `ground_drop`
- `history_risk`
- `user_mark`
- `sos`
- `none`

## Directions

Recommended values:

- `stop`
- `slow`
- `turn_left`
- `turn_right`
- `keep_left`
- `keep_right`
- `none`

## Sensor Values

Recommended values:

- `tof_front`
- `tof_left`
- `tof_right`
- `tof_down`
- `touch`
- `sos_button`
- `gps`
- `device`
- `unknown`

## POST `/api/risk-events`

Primary endpoint for ESP32-C5 risk uploads.

Request:

```json
{
  "device_id": "cane_001",
  "timestamp": "2026-07-10T12:00:00Z",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "level": "high",
  "risk_level": "high",
  "direction": "turn_left",
  "sensor": "tof_front",
  "distance_mm": 450,
  "battery": -1,
  "front_cm": 45,
  "left_cm": 130,
  "right_cm": 55,
  "down_cm": 45,
  "extra_json": "source=auto_detected;location=gps"
}
```

Required fields:

- `device_id`
- `lat`
- `lng`
- `risk_type`
- `level` or `risk_level`

Response:

```json
{
  "id": 1,
  "device_id": "cane_001",
  "timestamp": "2026-07-10T12:00:00+00:00",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "high",
  "level": "high",
  "direction": "turn_left",
  "sensor": "tof_front",
  "distance_mm": 450,
  "battery": -1,
  "front_cm": 45,
  "left_cm": 130,
  "right_cm": 55,
  "down_cm": 45,
  "extra_json": "source=auto_detected;location=gps"
}
```

Compatibility alias:

- `POST /api/events`

## GET `/api/risk-events`

Returns recent risk events for demos and map display.

Query:

- `limit`: default `200`, max `1000`

Compatibility alias:

- `GET /api/events`

## POST `/api/locations`

ESP32-C5 uploads GNSS/BeiDou/GPS or mock location. This endpoint is also the simple trajectory source for the map demo.

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "source": "gps",
  "provider": "beidou",
  "quality": "usable",
  "accuracy_m": 8.5,
  "hdop": 1.7,
  "fix_quality": 1,
  "satellite_count": 9
}
```

Recommended `provider` values:

- `beidou`
- `gps`
- `gnss`
- `mock`
- `unknown`

Recommended `quality` values:

- `good`
- `usable`
- `poor`
- `stale`
- `mock`

## GET `/api/locations/latest`

Query:

- `device_id`

Example:

```text
/api/locations/latest?device_id=cane_001
```

## GET `/api/locations/history`

Returns recent location records for one device, newest first. C can use it for a demo trajectory line.

Query:

- `device_id`
- `limit`, default `200`, max `1000`

Example:

```text
/api/locations/history?device_id=cane_001&limit=100
```

## GET `/api/risks/nearby`

ESP32-C5 and frontend use this endpoint to read collaborative history.

Query:

- `lat`
- `lng`
- `radius`, meters, default `80`

Response:

```json
{
  "risk_count": 3,
  "high_count": 2,
  "medium_count": 1,
  "max_level": "high",
  "recent_events": []
}
```

## POST `/api/ai/advice`

Backend AI advice endpoint. ESP32-C5 can call it for serial demo text, but local obstacle avoidance must not depend on it. The response includes a lightweight deep-learning risk score from `tiny-mlp-risk-v1`.

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "high",
  "front_cm": 45,
  "left_cm": 130,
  "right_cm": 55,
  "down_cm": 45,
  "accuracy_m": 8.5,
  "location_quality": "usable",
  "nearby_radius_m": 80
}
```

## POST `/api/ai/deep-risk`

Runs backend-side deep-learning risk inference without calling the LLM. This is useful for demos, tests, and map-side risk coloring.

Request:

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "medium",
  "front_cm": 75,
  "left_cm": 130,
  "right_cm": 55,
  "down_cm": 45,
  "accuracy_m": 8.5,
  "location_quality": "usable",
  "nearby_radius_m": 80
}
```

Response:

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "deep_learning": {
    "model": "tiny-mlp-risk-v1",
    "score": 0.61,
    "mlp_score": 0.61,
    "safety_floor": 0.48,
    "level": "medium",
    "confidence": 0.649,
    "features": {}
  },
  "nearby": {}
}
```

The ESP32-C5 does not run deep learning locally. It sends structured sensor data to the backend, and the backend returns enhanced scoring and advice.

## A-Side Upload Rules

A should upload:

- `ground_drop` immediately when down-facing ToF detects a drop.
- `front_obstacle` automatically when high risk persists.
- `user_mark` when touch electrode 1 is long-pressed.
- `sos` when the SOS button is held.

A should not upload every 100 ms sensor sample. Local feedback remains fully offline and independent of network status.

## Confidential Reference Handling

Private technical reports may be used locally to inform implementation choices, but their text, screenshots, and detailed internal structure must not be committed or published. Only project-owned interface fields and implementation decisions belong in this repository.
