# ESP32-C5 Smart Cane Arduino Firmware

This is the Arduino IDE / Arduino framework firmware for the ESP32-C5 collaborative smart cane demo.

It is designed for the hardware already tested in the supplied Arduino IDE screenshots:

- ESP32C5 Dev Module at `115200` baud
- I2C on `SDA=GPIO2`, `SCL=GPIO3`
- TCA9548A at `0x70`
- Four VL53L1X sensors through TCA channels `CH2/CH3/CH4/CH5`
- MPR121/HW-017 touch module on TCA `CH7` at `0x5A`
- Active buzzer on `GPIO4`
- PCA9685 at `0x40` for three vibration motors through MOS drivers

All pins, I2C addresses, thresholds, Wi-Fi, backend URL, and device ID are in `config.h`.

## Arduino Libraries

Install these from Arduino IDE Library Manager:

- `Adafruit MPR121`
- `Adafruit PWM Servo Driver Library`
- `VL53L1X` by Pololu
- `ArduinoJson`

`WiFi` and `HTTPClient` come from the ESP32 Arduino board package.

## Wiring

| Hardware | Connection |
| --- | --- |
| TCA9548A SDA/SCL | ESP32-C5 `GPIO2/GPIO3` |
| MPR121 SDA/SCL | TCA `CH7` by current bench wiring, or root I2C if `SMARTCANE_TOUCH_ON_TCA=0` |
| PCA9685 SDA/SCL | Root I2C `GPIO2/GPIO3` |
| VL53L1X front | TCA `CH2` |
| VL53L1X left | TCA `CH3` |
| VL53L1X right | TCA `CH4` |
| VL53L1X down | TCA `CH5` |
| PCA9685 CH0 | Left vibration motor MOS gate |
| PCA9685 CH1 | Right vibration motor MOS gate |
| PCA9685 CH2 | Center vibration motor MOS gate |
| Buzzer | `GPIO4` |
| SOS button | `GPIO5`, active low with internal pull-up |

Do not drive 1027 vibration motors directly from ESP32 GPIO. Use PCA9685 PWM output into MOS drivers.

If your final wiring returns to the original `CH0/CH1/CH2/CH3` ToF plan, only change these macros in `config.h`:

```cpp
#define SMARTCANE_TCA_CH_TOF_FRONT 0
#define SMARTCANE_TCA_CH_TOF_LEFT 1
#define SMARTCANE_TCA_CH_TOF_RIGHT 2
#define SMARTCANE_TCA_CH_TOF_DOWN 3
```

## Configure

Edit `config.h`:

```cpp
#define SMARTCANE_DEVICE_ID "cane_001"
#define SMARTCANE_WIFI_SSID "your_wifi"
#define SMARTCANE_WIFI_PASSWORD "your_password"
#define SMARTCANE_SERVER_BASE_URL "http://your_pc_lan_ip:8000"
#define SMARTCANE_MOCK_LAT 31.230400
#define SMARTCANE_MOCK_LNG 121.473700
```

Use your PC LAN IP, not `127.0.0.1`, because `127.0.0.1` from the ESP32 means the ESP32 itself.

## Open And Flash

1. Open Arduino IDE.
2. Open `firmware/smartcane_arduino/smartcane_arduino.ino`.
3. Select `ESP32C5 Dev Module`.
4. Select the COM port, for example `COM3`.
5. Install the libraries above.
6. Upload.
7. Open Serial Monitor at `115200 baud`, newline enabled.

## What Runs Locally

Local safety does not depend on Wi-Fi:

- Samples four ToF distances every `100 ms`.
- Detects front warning/danger by distance thresholds.
- Detects ground drops from the down-facing sensor.
- Fuses nearby history when available.
- Drives left/right/center vibration motors.
- Uses the buzzer only for high-risk cases, ground drops, and SOS.
- Debounces the SOS button and triggers after `2 s`.
- Reads MPR121 touch electrodes 0-5.

## Route And Risk Recording

Because the current purchase list does not include a verified GNSS module, route recording uses mock/mobile-replaceable coordinates from `config.h` by default.

Every `5 s`, the firmware:

- updates the simulated route point,
- stores it in a local ring buffer,
- uploads it to `POST /api/locations` when network mode is enabled.

High-risk local events and user marks are uploaded to `POST /api/risk-events`. Another device ID can then call `GET /api/risks/nearby` and use the historical risk count in local risk fusion.

Optional UART GNSS parsing is reserved behind `SMARTCANE_GNSS_ENABLED`, but it is disabled by default to match the currently verified hardware.

## Touch Controls

| Electrode | Action |
| --- | --- |
| E0 tap | Print current road/risk status, call backend deep-risk if online |
| E1 long press | Upload `user_mark` risk point |
| E2 tap | Repeat last vibration cue |
| E3 tap | Toggle local/network mode |
| E4 tap | Manual left cue |
| E5 tap | Manual right cue |

The firmware prints every touch event clearly to Serial.

## Serial Demo Commands

Use these in Serial Monitor:

```text
help
status
scan
mock auto
mock clear
mock warn
mock danger
mock drop
mock blocked
mock left
mock right
nearby
deep
mark
sos
mode
path
t0
t1long
t2
t3
t4
t5
```

These commands allow a full demo even without all touch or ToF hardware attached.

## Demo Flow

1. Start the backend.
2. Flash `cane_001`.
3. Watch Serial print four distances and risk state every second.
4. Put an obstacle in front: center motor vibrates; high danger also beeps.
5. Open left/right side space: the left/right motor suggests the safer direction.
6. Lift the down-facing sensor or use `mock drop`: ground drop triggers strong vibration and buzzer.
7. Run `mark` or long-press touch E1: backend records a user risk point.
8. Run `path`: local walked route ring buffer is printed.
9. Change `SMARTCANE_DEVICE_ID` to `cane_002`, flash again, and run `nearby`: the second cane sees the historical risk area.
10. Hold SOS for 2 seconds or run `sos`: buzzer, vibration, Serial SOS log, and backend upload.
