# ESP32-C5 Collaborative Smart Cane

This repository now uses the Arduino IDE / Arduino framework as the primary firmware path.

The system implements a practical ESP32-C5 multi-device collaborative smart cane:

- local obstacle and ground-drop risk detection,
- vibration and buzzer feedback,
- touch-handle and SOS interactions,
- route recording,
- backend risk-point upload,
- nearby historical risk lookup for collaborative mapping,
- backend-side lightweight deep-risk scoring and optional cloud LLM advice.

Local safety remains rule-based and offline-capable. Network, deep-risk scoring, and LLM advice only enrich phone/backend feedback and do not replace local obstacle avoidance.

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

frontend/SmartCane/
  Android Jetpack Compose frontend

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
| PCA9685 PWM/Servo Shield | Blue motor PWM board on TCA `CH6`, address `0x40` |
| 3 x 1027 3V vibration motors | Left, right, and center tactile feedback through PCA9685 channels |
| Active buzzer | High-risk, ground-drop, and SOS alert |
| Physical button | Short press requests Android voice input; long press triggers SOS |

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
| PCA9685 | TCA `CH6`, address `0x40` |
| Left vibration signal | PCA9685 `CH0` PWM/SIG, red to `V+`, black/brown to `GND` |
| Right vibration signal | PCA9685 `CH1` PWM/SIG, red to `V+`, black/brown to `GND` |
| Center vibration signal | PCA9685 `CH2` PWM/SIG, red to `V+`, black/brown to `GND` |
| Buzzer | `GPIO4` |
| Physical button | `GPIO5`, active low; short press `voice_request`, long press `sos` |

If you rewire ToF sensors back to `CH0/CH1/CH2/CH3`, edit only `firmware/smartcane_arduino/config.h`.

Power note for the standalone cane:

- ESP32-C5/SensairShuttle: power by USB-C 5V during development, or by the board-supported 3.7V Li-ion battery connector if available.
- PCA9685 blue motor board: motor `V+` can use the separate 3.7V battery already wired for the vibration motors.
- The ESP32 GND, PCA9685 logic GND, and motor battery GND must be common ground.
- PCA9685 logic `VCC` should be tied to ESP32 3.3V logic power; do not power ESP32 logic from the motor `V+` rail.
- The actual motor plugs are on blue PCA9685 positions `0/1/2`: left/right/center.

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

Use your PC LAN IP for local testing. If the cane connects to a phone hotspot,
connect the PC, ESP32-C5, and Android test phone to the same hotspot and use
the PC hotspot/LAN IPv4:

```cpp
#define SMARTCANE_SERVER_BASE_URL "http://118.31.221.165:8016"
```

Do not use `127.0.0.1` on the ESP32.

## Backend Setup

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8016
```

Health check:

```text
http://118.31.221.165:8016/api/health
```

Useful operation endpoints:

- `POST /api/locations`: route point upload
- `GET /api/locations/history?device_id=cane_001`
- `POST /api/risk-events`: risk-point upload
- `GET /api/risk-events`
- `GET /api/risks/nearby?lat=31.2304&lng=121.4737&radius=80`
- `POST /api/ai/deep-risk`
- `POST /api/ai/advice`

Android frontend compatibility endpoints:

- `GET /status`
- `GET /devices`
- `GET /events/latest`
- `POST /sos`
- `POST /telemetry`

Cloud LLM and speech services are optional. Put keys only in `backend/.env`; never commit real secrets.

## Closed-Loop Run

1. Start the backend.
2. Flash the Arduino firmware with `SMARTCANE_DEVICE_ID="cane_001"`.
3. Run `status` or `read` in Serial Monitor to print one ToF/risk snapshot.
4. Put an obstacle in front. The firmware samples every `500 ms`, prints one changed risk event, and uses vibration to suggest slow/left/right handling. One-shot distance obstacles are low-risk map points.
5. Keep the cane still with the same obstacle. The same place/same risk is not printed, vibrated, or uploaded repeatedly.
6. Leave more space on the left or right, clear the risk and trigger it again, or move into another location grid. The matching motor suggests the safer bypass direction and a new event can be recorded.
7. Lower the down-facing distance below `20 cm` to simulate a close curb/protrusion; the firmware uploads `down_obstacle` as low risk. Keep the down-facing distance from `20-90 cm` for normal ground/no step alert. Raise the valid down-facing distance strictly above `90 cm` for two confirmed frames to simulate a pit/drop; the firmware uploads `ground_drop`. No-target readings are reported separately as `down_no_target`.
8. Long-press touch electrode E1 or run `mark`. The backend records `user_mark` at the current route point.
9. Run `path` to print the local route/risk ring buffer.
10. Change `SMARTCANE_DEVICE_ID` to `cane_002`, flash again, and run `nearby`. The second cane receives historical risk statistics and fuses them into local risk.
11. Short-press the physical button or run `btn`: the cane uploads `voice_request`; the blind Android app enters voice interaction mode. The companion app does not receive this ordinary voice request.
12. Hold the physical button for 2 seconds or run `sos`: the cane vibrates, beeps, prints SOS, uploads `sos`, and the backend distinguishes it from `fall_detected`.
13. For fall detection, drop/tilt the BMI270 board onto a soft cushion and keep it sideways briefly. The cane uses buzzer only, uploads `fall_detected`, and the backend exposes it to both blind and companion app roles.

Serial commands are listed in `firmware/smartcane_arduino/README.md`.

## Android Frontend

Open this folder in Android Studio:

```text
D:\smartcane\frontend\SmartCane
```

The app backend address is configured in:

```text
frontend\SmartCane\local.properties
```

For a real phone on the same Wi-Fi, use the computer IPv4, for example:

```properties
BACKEND_BASE_URL=http://118.31.221.165:8016
```

For the Android Emulator, use:

```kotlin
const val BASE_URL = "http://118.31.221.165:8016"
```
