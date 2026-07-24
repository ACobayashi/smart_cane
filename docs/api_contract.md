# Smart Cane API Contract

This document is the shared contract for team members A, B, and C.

- A: ESP32-C5 Arduino firmware, sensors, local risk fusion, route upload.
- B: FastAPI backend, SQLite, AI advice service.
- C: map frontend and user-facing display.

The firmware uses Arduino IDE / Arduino framework and builds from `firmware/smartcane_arduino/smartcane_arduino.ino`.

## Software Functions

SmartCane is split into four visible parts:

- User app: shows live map, current position, nearby risk points, AI advice, SOS, and device status.
- Companion app: shows the companion username, linked user status, user location, risk records, SOS alerts, and device status.
- FastAPI backend: receives ESP32-C5 and Android uploads, writes SQLite, reads collaborative risk history, and calls the cloud LLM.
- ESP32-C5 firmware: fuses ToF, touch/button, IMU, and location data, then uploads only meaningful risk events.

## Required Team Interfaces

These endpoints are the stable contract between B and C:

| Function | Method | Path | Used By |
| --- | --- | --- | --- |
| Map risk points | `GET` | `/api/risk-events` | Android map |
| Location upload | `POST` | `/api/locations` | ESP32-C5 / Android |
| Latest location | `GET` | `/api/locations/latest?device_id=...` | Android map / companion |
| Location history | `GET` | `/api/locations/history?device_id=...` | Android route line |
| User risk mark | `POST` | `/api/risk-events` | Android user mark |
| Nearby shared risks | `GET` | `/api/risks/nearby?lat=...&lng=...&radius=...` | ESP32-C5 / Android |
| AI advice | `POST` | `/api/ai/advice` or `/api/ai-advice` | Android / ESP32-C5 integration test |
| Backend status | `GET` | `/api/ai/status` | Demo check |

Map point fields required by frontend:

- `lat`
- `lng`
- `risk_type`
- `level` or `risk_level`
- `ai_message`

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
- `ground_step`
- `history_risk`
- `user_mark`
- `sos`
- `fall_detected`
- `voice_request`
- `prolonged_obstacle`
- `approaching_obstacle`
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
- `bmi270_imu`
- `tof_trend`
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
  "level": "low",
  "risk_level": "low",
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
  "risk_level": "low",
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

Returns recent risk events for map display and integration checks.

Query:

- `limit`: default `200`, max `1000`

Compatibility alias:

- `GET /api/events`

## POST `/api/locations`

ESP32-C5 uploads GNSS/BeiDou/GPS or fallback location. This endpoint is also the simple trajectory source for the map.

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

Returns recent location records for one device, newest first. The mobile client can use it for a trajectory line.

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

## GET `/api/hardware/profile`

Returns the tested hardware wiring and active levels for frontend display and debugging.

Important values:

- ToF: front `TCA CH2`, left `CH3`, right `CH4`, down `CH5`
- Touch: MPR121/HW-017 on `TCA CH7`
- Buzzer: `GPIO4`, low-level trigger
- Button: `GPIO5`, active low. Short press sends `voice_request` to blind Android app; long press sends `sos`.
- Motors: blue PCA9685 PWM/Servo Shield at `0x40` on TCA `CH6`, left `CH0`, right `CH1`, center `CH2` for the actual bench wiring

## POST `/api/sensor-frames`

Preferred full-chain frame upload from the ESP32-C5 or Android integration tools. The backend computes a hardware-aware immediate risk score, returns frontend voice/feedback fields, and separately computes `map_weight` for collaborative map storage.

Current slanted-cane thresholds:

- Front: low-risk distance warning below `110cm`; readings below `35cm` get stronger local buzzer/vibration but are still stored as low-risk distance events.
- Left/right side: warning below `20cm`, danger below `12cm`, because normal sweeping can touch nearby objects.
- Front sensor: `front_obstacle` is low risk below `110cm`; near-collision readings below `35cm` are urgent locally but low-weight on the shared map.
- Down sensor: `down_obstacle` low risk below `20cm`; `20-90cm` is normal; `ground_drop` / `ground_step` medium risk only when the down-facing valid distance is strictly above `90cm`.
- Risk tier policy: SOS and `fall_detected` are `high`; `ground_drop` and `ground_step` are `medium`; ordinary ToF sweep risks (`front_obstacle`, `left_obstacle`, `right_obstacle`, `down_obstacle`) are `low`.
- Ordinary one-shot ToF obstacles are useful for immediate phone feedback but have low `map_weight`; fall/SOS have the highest map weight, ground drop and user mark are stored as collaborative map points, and prolonged/approaching obstacle alerts use small map weights.

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "front_cm": 42,
  "left_cm": 130,
  "right_cm": 50,
  "down_cm": 45,
  "battery": 88,
  "source": "esp32c5"
}
```

Physical-button short press payload:

```json
{
  "device_id": "cane_001",
  "front_cm": 180,
  "left_cm": 150,
  "right_cm": 150,
  "down_cm": 45,
  "button_event": "short_press",
  "alert_type": "voice_request",
  "source": "esp32c5"
}
```

The response has `risk.risk_type="voice_request"` and `risk.feedback.action="start_voice_input"`. `/api/alerts/latest?role=blind&deviceId=cane_001` returns it; the companion role does not.

Response:

```json
{
  "accepted": true,
  "risk": {
    "risk_type": "front_obstacle",
    "risk_level": "low",
    "risk_score": 34.0,
    "direction": "turn_left",
    "voice_prompt": "前方 42 厘米有障碍，左侧较空，请向左慢行。",
    "feedback": {
      "buzzer": {"enabled": false, "beeps": 0, "pattern": "none"},
      "vibration": {"left": 45, "right": 0, "center": 45, "duration_ms": 280}
    }
  },
  "stored_event": {}
}
```

Important returned risk fields for Android/frontend:

- `risk.risk_reason` / `risk.riskReason`: human-readable reason, for example `前方距离 42cm 小于斜持盲杖前向阈值 110/35cm`.
- `risk.map_weight` / `risk.mapWeight`: collaborative-map weight. Ordinary one-shot ToF obstacles are low weight; fall/SOS are highest, ground-drop/user-mark are stored as collaborative map points, prolonged/approaching obstacle alerts are companion hints.
- `risk.voice_prompt`: Chinese voice text for the blind-side app.
- `risk.feedback`: suggested buzzer/vibration action from backend.

## Amap / Gaode Backend Proxy

The Web service key stays in `backend/.env` as `AMAP_WEB_KEY`. Android key stays in `frontend/SmartCane/local.properties` as `AMAP_ANDROID_KEY`.

Frontend endpoints:

- `GET /api/map/status`
- `GET /api/map/geocode?address=...&city=...`
- `GET /api/map/regeo?lat=...&lng=...&coordsys=gps`
- `GET /api/map/risk-points?lat=...&lng=...&radius=500`
- `POST /api/navigation/risk-aware-route`
- `POST /api/navigation/voice-route`

Risk-aware route request:

```json
{
  "device_id": "cane_001",
  "origin_lat": 31.2304,
  "origin_lng": 121.4737,
  "destination_text": "上海人民广场",
  "city": "上海",
  "coordsys": "gps"
}
```

The route score combines walking cost with stored collaborative risk points near the polyline. Lower `combined_score` is safer. `voice_prompt` is enhanced by the cloud LLM when configured and falls back to rules when unavailable.

## POST `/api/ai/advice`

Backend AI advice endpoint. ESP32-C5 can call it for assisted text, but local obstacle avoidance must not depend on it. The response includes a lightweight deep-learning risk score from `tiny-mlp-risk-tier-v2`.

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "low",
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

Runs backend-side deep-learning risk inference without calling the LLM. This is useful for integration tests and map-side risk coloring.

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
    "model": "tiny-mlp-risk-tier-v2",
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

## Android Frontend Compatibility

The Android app under `frontend/SmartCane` uses a compact compatibility API. These paths read and write the same backend SQLite tables as the `/api/...` endpoints.

### GET `/status`

Response:

```json
{
  "online": true,
  "message": "后端已连接，正在记录路线和风险点",
  "deviceCount": 2
}
```

### GET `/devices`

Response:

```json
{
  "devices": [
    {
      "deviceId": "cane_001",
      "name": "SmartCane cane_001",
      "online": true,
      "battery": null,
      "lastSeen": "2026-07-19T02:30:00+00:00"
    }
  ]
}
```

### GET `/events/latest`

Response:

```json
{
  "events": [
    {
      "id": 1,
      "deviceId": "cane_001",
      "riskType": "ground_drop",
      "riskLevel": "high",
      "distance": 950,
      "message": "下视距离约 95 厘米，可能有台阶、坑洼或落差，请停止前进。",
      "latitude": 31.2304,
      "longitude": 121.4737,
      "timestamp": "2026-07-19T02:30:00+00:00"
    }
  ]
}
```

### POST `/sos`

Request:

```json
{
  "deviceId": "cane_001",
  "latitude": 31.2304,
  "longitude": 121.4737,
  "message": "Android SOS"
}
```

### POST `/telemetry`

Optional simulator-compatible upload:

```json
{
  "deviceId": "cane_001",
  "battery": 88,
  "frontDistanceMm": 450,
  "leftDistanceMm": 1200,
  "rightDistanceMm": 1300,
  "downDistanceMm": 450,
  "latitude": 31.2304,
  "longitude": 121.4737
}
```

## A-Side Upload Rules

A should upload:

- `ground_drop` immediately when down-facing ToF detects a clear drop/pit.
- `down_obstacle` below `20cm`; no step/drop alarm from `20-90cm`; `ground_drop` / `ground_step` only when down-facing distance is strictly above `90cm` after confirmation.
- `front_obstacle` / `left_obstacle` / `right_obstacle` as low-risk distance events after local de-duplication; they keep a small map weight.
- `user_mark` when touch electrode 1 is long-pressed.
- `sos` when the SOS button is held.

A should not upload every 100 ms sensor sample. Local feedback remains fully offline and independent of network status.

## Confidential Reference Handling

Private technical reports may be used locally to inform implementation choices, but their text, screenshots, and detailed internal structure must not be committed or published. Only project-owned interface fields and implementation decisions belong in this repository.
