# Smart Cane Backend

FastAPI + SQLite backend for the ESP32-C5 smart cane project.

It stores risk events, stores device locations, returns nearby risk statistics, runs a lightweight backend-side deep-learning risk scorer, and optionally calls a cloud LLM/STT provider for demo advice and voice-command parsing.

Do not put real API keys in Git. Keep secrets in `backend/.env`.

## Install

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

## Environment

Copy `.env.example` to `.env`, then fill provider keys locally.

Supported LLM providers:

- `LLM_PROVIDER=ark`
- `LLM_PROVIDER=openai`

If no key is configured, normal event/location/map APIs still work. AI advice falls back to rule-based text.

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
| GET | `/api/risks/nearby` | nearby collaborative risk summary |
| POST | `/api/ai/deep-risk` | lightweight deep-learning risk scoring |
| POST | `/api/ai/advice` | optional LLM advice |
| POST | `/api/voice/text-command` | optional text command parsing |
| POST | `/api/voice/transcribe` | optional audio transcription |
| POST | `/api/voice/command` | optional audio command parsing |

See `D:\smartcane\docs\api_contract.md` for the shared A/B/C contract.

## Risk Upload Example

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "ground_drop",
  "level": "high",
  "risk_level": "high",
  "direction": "stop",
  "sensor": "tof_down",
  "distance_mm": 950,
  "battery": -1,
  "front_cm": 180,
  "left_cm": 130,
  "right_cm": 120,
  "down_cm": 95,
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

The model is `tiny-mlp-risk-v1`, implemented in `backend/deep_model.py`. It runs on the backend only; ESP32-C5 local obstacle avoidance remains rule-based and offline-safe.

## Collaborative Demo

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
