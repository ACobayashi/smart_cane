# ESP32-C5 Smart Cane Native ESP-IDF Project

The ESP32-C5 firmware in this repository has been fully migrated to a native ESP-IDF project.

It no longer uses:

- Arduino IDE project structure
- `.ino` entry files
- `Arduino.h`
- `setup()` / `loop()`
- Arduino APIs
- Arduino as an ESP-IDF component

The `backend/` directory is still kept as the FastAPI + SQLite + cloud AI service for risk upload, nearby risk lookup, deep-learning risk scoring, AI advice, and voice endpoints.

The shared API contract for team members A/B/C is in `docs/api_contract.md`.

## Project Info

- Target chip: `esp32c5`
- Recommended ESP-IDF version: `v6.0.2`
- Firmware entry point: `app_main(void)` in `main/main.c`
- Runtime model: FreeRTOS tasks
- Build system: ESP-IDF CMake
- Partition table: `partitions.csv`, factory app partition is `1536K`

## Structure

```text
.
|-- CMakeLists.txt
|-- sdkconfig.defaults
|-- partitions.csv
|-- main/
|   |-- CMakeLists.txt
|   |-- main.c
|   |-- board_config.h
|   |-- app_tasks.c
|   `-- app_tasks.h
|-- components/
|   |-- common/
|   |-- i2c_bus/
|   |-- tof_sensors/
|   |-- touch_input/
|   |-- vibration_motor/
|   |-- buzzer/
|   |-- buttons/
|   |-- gps_location/
|   |-- risk_logic/
|   `-- communication/
|-- backend/
|   |-- main.py
|   `-- deep_model.py
`-- docs/
```

## Completed Firmware Features

- TCA9548A I2C multiplexer channel selection
- Four VL53L1X ToF sensor slots: front, left, right, down
- Mock ToF mode for bench demos without hardware
- MPR121 touch handle with tap, long press, and double click
- GPIO fallback for touch input when MPR121 is not available
- PCA9685 PWM vibration motor control for left, right, and center motors
- Non-blocking active buzzer patterns
- SOS button debounce and long-press trigger
- UART GPS/GNSS NMEA parsing with mock fallback
- Local obstacle avoidance and ground-drop detection
- Nearby historical risk fusion
- Native Wi-Fi STA connection
- HTTP JSON upload for events and location
- GNSS/BeiDou/GPS provider and location quality upload
- HTTP nearby risk lookup and AI advice request
- Backend lightweight deep-learning risk scoring through `/api/ai/deep-risk`
- ESP-NOW local device status broadcast and receive
- FreeRTOS tasks: `sensor_task`, `logic_task`, `feedback_task`, `communication_task`, `debug_task`

## Role A Checklist

If you are member A, focus on the native ESP-IDF device side:

- Flash and monitor the ESP32-C5 firmware.
- Update Wi-Fi, backend URL, and `SMARTCANE_DEVICE_ID` in `components/common/include/smartcane_config.h`.
- Keep `SMARTCANE_MOCK_SENSOR_MODE` enabled until real VL53L1X modules are wired.
- Verify TCA9548A, four ToF modules, MPR121, PCA9685, buzzer, SOS button, and GPS one by one.
- Verify GNSS/BeiDou NMEA output, satellite count, HDOP, and location quality logs.
- Calibrate `SMARTCANE_FRONT_WARN_CM`, `SMARTCANE_FRONT_DANGER_CM`, `SMARTCANE_SIDE_SAFE_CM`, `SMARTCANE_GROUND_BASE_CM`, and `SMARTCANE_GROUND_DROP_THRESHOLD_CM`.
- Confirm uploads reach `POST /api/risk-events`.
- Confirm the second device can read collaborative history through `GET /api/risks/nearby`.

## Hardware Wiring

| Module | ESP32-C5 connection |
| --- | --- |
| TCA9548A / MPR121 / PCA9685 SDA | GPIO 8 |
| TCA9548A / MPR121 / PCA9685 SCL | GPIO 9 |
| TCA9548A CH0 | Front VL53L1X |
| TCA9548A CH1 | Left VL53L1X |
| TCA9548A CH2 | Right VL53L1X |
| TCA9548A CH3 | Down-facing VL53L1X |
| PCA9685 CH0 | Left vibration motor MOS input |
| PCA9685 CH1 | Right vibration motor MOS input |
| PCA9685 CH2 | Center vibration motor MOS input |
| SOS button | GPIO 4, active low by default |
| Active buzzer | GPIO 5 |
| GPS TX | ESP32-C5 GPIO 18 |
| GPS RX | ESP32-C5 GPIO 19, optional |

All GPIOs, I2C addresses, thresholds, Wi-Fi credentials, and backend URL are configured in:

```text
components/common/include/smartcane_config.h
```

## Build

Open an ESP-IDF PowerShell or ESP-IDF Command Prompt, then run:

```bash
cd D:\smartcane
idf.py set-target esp32c5
idf.py fullclean
idf.py build
```

The project has been verified to build successfully with ESP-IDF v6.0.2.

## Flash And Monitor

```bash
idf.py -p COMx flash monitor
```

Replace `COMx` with your board serial port, for example:

```bash
idf.py -p COM5 flash monitor
```

Expected monitor logs include:

```text
[APP] ESP32-C5 blind assistance system starting...
[I2C] I2C bus ready ...
[SENSOR] front=xxcm left=xxcm right=xxcm down=xxcm
[LOGIC] risk=medium/high ...
[FEEDBACK] motor/buzzer status
[COMM] Wi-Fi / ESP-NOW / HTTP status
```

## Backend

```powershell
cd backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

Then update the backend URL in `components/common/include/smartcane_config.h`:

```c
#define SMARTCANE_SERVER_BASE_URL "http://192.168.1.100:8000"
```

Do not use `127.0.0.1` for the ESP32-C5, because that points to the device itself.

## Hardware Items Still Requiring Real-World Verification

- VL53L1X native ranging register behavior across module batches
- MPR121 threshold tuning for the final handle material
- GPS module baud rate and indoor fix behavior
- PCA9685 + MOS drive strength for 1027 vibration motors
- ESP-NOW range and channel behavior in the target environment

## Migration Report

See [MIGRATION_REPORT.md](MIGRATION_REPORT.md).
