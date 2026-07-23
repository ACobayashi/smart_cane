# Smart Cane Backend

FastAPI + SQLite backend for the ESP32-C5 smart cane project.

It stores risk events, stores device locations, returns nearby risk statistics, runs a lightweight backend-side deep-learning risk scorer, and optionally calls a cloud LLM/STT provider for assisted advice and voice-command parsing.

Do not put real API keys in Git. Keep secrets in `backend/.env`.

The ESP32-C5 firmware now uploads both event-driven risk records and periodic real sensor frames. The local safety loop remains on the cane; this backend stores collaborative map risk points, exposes alerts to the blind/companion Android roles, and calls Amap/LLM services when configured.

## Install

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8016
```

## Environment

Copy `.env.example` to `.env`, then fill provider keys locally.

Supported LLM providers:

- `LLM_PROVIDER=ark`
- `LLM_PROVIDER=openai`

If no key is configured, normal event/location/map APIs still work. AI advice falls back to rule-based text.

Amap / Gaode:

- Put the Web service key in `AMAP_WEB_KEY`.
- Keep Android platform key in `frontend/SmartCane/local.properties` as `AMAP_ANDROID_KEY`.
- The ESP32-C5 and Android app call this backend; the Amap Web key is not exposed to firmware or frontend code.

## Main APIs

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | health check |
| POST | `/api/risk-events` | primary ESP32-C5 risk upload |
| GET | `/api/risk-events` | list risk events |
| POST | `/api/events` | compatibility alias |
| GET | `/api/events` | compatibility alias |
| POST | `/api/locations` | upload device location |
| GET | `/api/locations/latest` | latest location for one device |
| GET | `/api/locations/history` | recent location history for one device |
| POST | `/api/sensor-frames` | hardware-adapted four-ToF frame upload and feedback analysis |
| GET | `/api/alerts/latest` | blind/companion alerts; `voice_request` goes only to blind side |
| GET | `/api/hardware/profile` | ESP32-C5 wiring, active levels, touch, and motor mapping |
| GET | `/api/risks/nearby` | nearby collaborative risk summary |
| GET | `/api/map/status` | Amap key/config status |
| GET | `/api/map/geocode` | Amap address to coordinate |
| GET | `/api/map/regeo` | Amap coordinate to address |
| GET | `/api/map/risk-points` | map risk points from SQLite |
| POST | `/api/navigation/risk-aware-route` | Amap walking route plus collaborative risk score |
| POST | `/api/navigation/voice-route` | parse simple voice destination text and plan safer route |
| POST | `/api/ai/deep-risk` | lightweight deep-learning risk scoring |
| POST | `/api/ai/advice` | optional LLM advice |
| POST | `/api/voice/text-command` | optional text command parsing |
| POST | `/api/voice/transcribe` | optional audio transcription |
| POST | `/api/voice/command` | optional audio command parsing |

See `D:\smartcane\docs\api_contract.md` for the shared A/B/C contract.

## Android Frontend Compatibility APIs

The Android app in `D:\smartcane\frontend\SmartCane` uses these legacy-compatible paths:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/status` | app server status card |
| GET | `/devices` | app device list |
| GET | `/events/latest` | latest risk events using camelCase fields |
| POST | `/sos` | Android app SOS upload |
| POST | `/telemetry` | optional simulator/device telemetry upload |

These endpoints read and write the same SQLite tables as `/api/risk-events` and `/api/locations`.

## Hardware-Adapted Sensor Frame

`POST /api/sensor-frames` is the preferred full-chain upload from ESP32-C5 or a simulator. It knows the tested wiring:

- front ToF: TCA `CH2`
- left ToF: TCA `CH3`
- right ToF: TCA `CH4`
- down ToF: TCA `CH5`
- touch MPR121: TCA `CH7`
- buzzer: GPIO `4`, low-level trigger
- physical button: GPIO `5`, active low. Short press requests Android voice input; long press uploads `sos`.
- vibration motors: PCA9685 blue board on root I2C `0x40`; left `CH8`, right `CH9`, center `CH10`. Do not use ESP32 GPIO `8/9/10` because they are reserved by the BMI270/BMM350 shuttle board path.
- built-in BMI270 IMU: fall detection; BMM350 is heading reference only, not GPS/location

If the Android app has uploaded a recent non-mock Amap location for the same `device_id`, the backend uses that phone location for risk events and sensor frames. Firmware mock coordinates are only a fallback.

Example:

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

Response includes `risk.risk_score`, `risk.voice_prompt`, and `risk.feedback`. The frontend can speak `voice_prompt`; firmware can keep using local buzzer/motor rules offline.

Button short press from firmware is sent as `alert_type="voice_request"` and `button_event="short_press"`. The backend stores it as a low-level operation event, exposes it only to the blind app through `/api/alerts/latest`, and does not notify the companion side or mark it as a map risk.

Fall upload example:

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "fall_detected": true,
  "fall_stage": "confirmed",
  "fall_confidence": 0.92,
  "accel_total_g": 2.7,
  "source": "esp32c5"
}
```

`fall_detected` is stored as a high-priority event. `/api/alerts/latest?role=blind&deviceId=cane_001` and `/api/alerts/latest?role=companion&deviceId=cane_001` both return it. The response feedback sets all vibration motors to `0` because a fallen user may not be holding the cane.

Companion-only obstacle escalation:

- `prolonged_obstacle`: same obstacle persists for a long time.
- `approaching_obstacle`: front distance keeps decreasing over the configured window.

Ordinary short obstacle detections remain local/normal risk records and do not page the companion side.

## Amap Risk-Aware Navigation

`POST /api/navigation/risk-aware-route` accepts coordinates or address text. It calls Amap walking navigation, overlays stored risk points from SQLite, and returns the route with the lowest combined risk.

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

The `voice_prompt` field is LLM-enhanced when `ARK_API_KEY` or `OPENAI_API_KEY` is configured, and rule-based otherwise.

## Risk Upload Example

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "ground_step",
  "level": "medium",
  "risk_level": "medium",
  "direction": "stop",
  "sensor": "tof_down",
  "distance_mm": 950,
  "battery": -1,
  "front_cm": 180,
  "left_cm": 130,
  "right_cm": 120,
  "down_cm": 50,
  "extra_json": "source=auto_detected"
}
```

## Deep-Learning Risk Example

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "low",
  "front_cm": 75,
  "left_cm": 130,
  "right_cm": 55,
  "down_cm": 45,
  "accuracy_m": 8.5,
  "location_quality": "usable",
  "nearby_radius_m": 80
}
```

The model is `tiny-mlp-risk-tier-v2`, implemented in `backend/deep_model.py`. It runs on the backend only; ESP32-C5 local obstacle avoidance remains rule-based and offline-safe.

## Collaborative Run

1. Start the backend.
2. Flash `cane_001`, trigger a `user_mark`, `front_obstacle`, or `ground_drop`.
3. Open `/api/risk-events` and confirm the event is saved.
4. Change firmware `SMARTCANE_DEVICE_ID` to `cane_002`.
5. Flash or restart the second device.
6. The second device calls `/api/risks/nearby` and fuses nearby history with its local sensor risk.

## Location Upload Example

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
