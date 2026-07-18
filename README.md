# ESP32-C5 Collaborative Smart Cane

This repository now uses the Arduino IDE / Arduino framework as the primary firmware path.

The system demonstrates a practical ESP32-C5 multi-device collaborative smart cane:

- local obstacle and ground-drop risk detection,
- vibration and buzzer feedback,
- touch-handle and SOS interactions,
- route recording,
- backend risk-point upload,
- nearby historical risk lookup for collaborative mapping,
- backend-side lightweight deep-risk scoring and optional cloud LLM advice.

Local safety remains rule-based and offline-capable. Network, deep-risk scoring, and LLM advice only enhance demos and do not replace local obstacle avoidance.

Private reference PDFs and API keys must not be committed or uploaded.

## Repository Structure

```text
firmware/smartcane_arduino/
  smartcane_arduino.ino
  config.h
  i2c_bus.*
  tof_sensors.*
  touch_handle.*
  vibration.*
  buttons.*
  buzzer.*
  risk_logic.*
  network_client.*
  data_model.h
  README.md

backend/
  main.py
  deep_model.py
  requirements.txt
  README.md

docs/
  api_contract.md
```

## Hardware

| Module | Role |
| --- | --- |
| ESP32-C5 SensairShuttle or compatible ESP32-C5 Arduino board | Main controller |
| TCA9548A | I2C multiplexer for four identical VL53L1X ToF sensors |
| 4 x VL53L1X | Front, left, right, and down distance sensing |
| MPR121 / HW-017 | Capacitive touch handle |
| PCA9685 | PWM driver for three vibration motors |
| 3 x 1027 3V vibration motors | Left, right, and center tactile feedback through MOS drivers |
| Active buzzer | High-risk, ground-drop, and SOS alert |
| SOS button | Physical long-press emergency trigger |

Recommended current bench wiring from the Arduino screenshots:

| Hardware | ESP32-C5 / TCA / PCA9685 connection |
| --- | --- |
| I2C SDA | `GPIO2` |
| I2C SCL | `GPIO3` |
| TCA9548A address | `0x70` |
| Front VL53L1X | TCA `CH2` |
| Left VL53L1X | TCA `CH3` |
| Right VL53L1X | TCA `CH4` |
| Down VL53L1X | TCA `CH5` |
| MPR121 | TCA `CH7`, address `0x5A` |
| PCA9685 | root I2C, address `0x40` |
| PCA9685 CH0/CH1/CH2 | left/right/center motor MOS gate |
| Buzzer | `GPIO4` |
| SOS button | `GPIO5`, active low |

If you rewire ToF sensors back to `CH0/CH1/CH2/CH3`, edit only `firmware/smartcane_arduino/config.h`.

## Arduino Libraries

Install these in Arduino IDE Library Manager:

- `Adafruit MPR121`
- `Adafruit PWM Servo Driver Library`
- `VL53L1X` by Pololu
- `ArduinoJson`

`WiFi` and `HTTPClient` come with the ESP32 Arduino board package.

## Firmware Setup

Open:

```text
D:\smartcane\firmware\smartcane_arduino\smartcane_arduino.ino
```

In Arduino IDE:

1. Select board `ESP32C5 Dev Module`.
2. Select the serial port, for example `COM3`.
3. Set Serial Monitor to `115200 baud`.
4. Upload.

Configure device, Wi-Fi, backend URL, thresholds, GPIO, I2C channels, and mock route values in:

```text
firmware/smartcane_arduino/config.h
```

Use your PC LAN IP for the backend URL, for example:

```cpp
#define SMARTCANE_SERVER_BASE_URL "http://192.168.1.100:8000"
```

Do not use `127.0.0.1` on the ESP32.

## Backend Setup

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

Health check:

```text
http://127.0.0.1:8000/api/health
```

Useful demo endpoints:

- `POST /api/locations`: route point upload
- `GET /api/locations/history?device_id=cane_001`
- `POST /api/risk-events`: risk-point upload
- `GET /api/risk-events`
- `GET /api/risks/nearby?lat=31.2304&lng=121.4737&radius=80`
- `POST /api/ai/deep-risk`
- `POST /api/ai/advice`

Cloud LLM and speech services are optional. Put keys only in `backend/.env`; never commit real secrets.

## Closed-Loop Demo

1. Start the backend.
2. Flash the Arduino firmware with `SMARTCANE_DEVICE_ID="cane_001"`.
3. Serial shows four ToF distances and the fused risk state every second.
4. Put an obstacle in front. The center motor vibrates; high danger beeps.
5. Leave more space on the left or right. The matching motor suggests the safer bypass direction.
6. Raise the down-facing sensor or run `mock drop`. The firmware detects a ground-drop risk, vibrates strongly, beeps, and uploads `ground_drop`.
7. Long-press touch electrode E1 or run `mark`. The backend records `user_mark` at the current route point.
8. Run `path` to print the local route ring buffer.
9. Change `SMARTCANE_DEVICE_ID` to `cane_002`, flash again, and run `nearby`. The second cane receives historical risk statistics and fuses them into local risk.
10. Hold the SOS button for 2 seconds or run `sos`. The cane vibrates, beeps, prints SOS, and uploads `sos`.

Serial commands are listed in `firmware/smartcane_arduino/README.md`.
