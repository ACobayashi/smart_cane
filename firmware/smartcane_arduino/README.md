# ESP32-C5 Smart Cane Arduino Firmware

This is the Arduino IDE / Arduino framework firmware for the ESP32-C5 collaborative smart cane.

It is designed for the hardware already tested in the supplied Arduino IDE screenshots:

- ESP32C5 Dev Module at `115200` baud
- I2C on `SDA=GPIO2`, `SCL=GPIO3`
- TCA9548A at `0x70`
- Four VL53L1X sensors through TCA channels `CH2/CH3/CH4/CH5`
- MPR121/HW-017 touch module on TCA `CH7` at `0x5A`
- Active buzzer on `GPIO4`
- ESP-SensairShuttle BMI270 motion sensor for fall detection
- PCA9685 vibration feedback on the blue board through TCA `CH6`, channels `CH0/CH1/CH2`

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
| VL53L1X front | TCA `CH2` |
| VL53L1X left | TCA `CH3` |
| VL53L1X right | TCA `CH4` |
| VL53L1X down | TCA `CH5` |
| PCA9685 blue board | TCA `CH6`, address `0x40` |
| Left vibration motor | PCA9685 `CH0`: signal to `PWM/SIG`, red to `V+`, black/brown to `GND` |
| Right vibration motor | PCA9685 `CH1`: signal to `PWM/SIG`, red to `V+`, black/brown to `GND` |
| Center vibration motor | PCA9685 `CH2`: signal to `PWM/SIG`, red to `V+`, black/brown to `GND` |
| Buzzer | `GPIO4` |
| Physical button | `GPIO5`, active low with internal pull-up; short press requests Android voice input, long press triggers SOS |
| BMI270 | SensairShuttle BMI270/BMM350 ShuttleBoard; current hardware appears at I2C `0x69`; firmware probes both `0x68/0x69` and uploads the Bosch BMI270 config before reading acceleration |

Keep each three-pin motor plug orientation unchanged: black/brown to `GND`, red to `V+`, and white/orange/yellow to signal/PWM. Do not put motor signals on ESP32 GPIO while using the full cane build; the blue PCA9685 board is controlled through I2C on TCA `CH6`. The actual motor plugs are on blue board positions `0/1/2`: left/right/center.

For the blue PCA9685 shield, the green terminal `V+ / GND` is the motor power path. The PCA9685 chip itself also needs a valid logic `VCC / GND` path. On shield variants with `VCC select`, make sure only one side is selected. For ESP32-C5 tests, prefer the 3.3 V logic side when the board supports it; never short the 5 V and 3.3 V sides together. With the replacement motor board installed, keep the existing I2C wiring and verify `pca` shows `0x40` before testing `m1/m2/m3`.

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
#define SMARTCANE_WIFI_SSID "xin"
#define SMARTCANE_WIFI_PASSWORD "your_wifi_password"
#define SMARTCANE_SERVER_BASE_URL "http://118.31.221.165:8016"
#define SMARTCANE_MOCK_LAT 31.230400
#define SMARTCANE_MOCK_LNG 121.473700
```

For the current group deployment, keep `SMARTCANE_SERVER_BASE_URL` pointed at `http://118.31.221.165:8016`. For temporary local testing, use your PC LAN IP, not `127.0.0.1`, because `127.0.0.1` from the ESP32 means the ESP32 itself.

For an independent product build, keep the ESP32-C5 on a reachable Wi-Fi/hotspot and point `SMARTCANE_SERVER_BASE_URL` at a cloud or LAN backend. The Android phone supplies the real Amap/GPS location to the backend; the cane-side coordinates are only a fallback until a GNSS module or phone-to-cane location bridge is added.

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

- Samples four ToF distances every `500 ms`.
- Detects front warning/danger by distance thresholds.
- Detects close ground obstacles below `20 cm`; treats `20-90 cm` as normal; detects steps, pits, or suspended ground only when the down-facing valid distance is strictly above `90 cm`.
- Fuses nearby history when available.
- Drives obstacle vibration through PCA9685 `CH0/CH1/CH2` on TCA `CH6`.
- Uses the buzzer only for high-risk cases, ground drops, and SOS.
- Debounces the physical button. Short press uploads `voice_request` for the blind Android app; long press after `2 s` uploads `sos`.
- Reads MPR121 touch electrodes 0-5.
- Reads BMI270 acceleration and raises `fall_detected` on large vertical motion/freefall/impact plus a still sideways posture. Fall alert uses buzzer and backend upload only, no vibration.

## Route And Risk Recording

Because the current purchase list does not include a verified GNSS module, route recording should use Android/Amap location from the backend. The firmware keeps fallback coordinates in `config.h` so hardware-only tests still upload valid JSON.

When the location moves into a new small grid cell, the firmware:

- stores it in a local ring buffer,
- uploads it to `POST /api/locations` when network mode is enabled.

Local risk events are event-driven: the same risk type/level/direction in the same location grid is logged, vibrated, and uploaded only once. It is reported again after the risk changes, clears and reappears, or the user moves into another grid cell. User marks are uploaded to `POST /api/risk-events`. Another device ID can then call `GET /api/risks/nearby` and use the historical risk count in local risk fusion.

`SMARTCANE_MOCK_ROUTE_ENABLED` is `0` by default for bench testing. Keep it off for product tests.

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

## Serial Product Commands

Use these in Serial Monitor:

```text
help
status
read
scan
pca
imurescan
imustream on
imustream off
nearby
deep
mark
sos
btn
mode
path
vib status
vib left
vib right
vib center
vib all
vib stop
imu
imuraw
t0
t1long
t2
t3
t4
t5
```

Use `scan`, `pca`, `imu`, `read`, `vib all`, and `beep` for real hardware checks.

## Product Test Flow

1. Start the backend.
2. Flash `cane_001`.
3. Run `status` or `read` once to see the current distances and risk state.
4. Put an obstacle in front: Serial prints one risk event, center motor vibrates, and high danger also beeps.
5. Keep the obstacle still: the same place/same risk is not printed repeatedly.
6. Open left/right side space or move to another grid cell: the left/right motor suggests the safer direction and a new event can be recorded.
7. Point the down-facing sensor below `20 cm`: `down_obstacle` is uploaded as low risk. Keep it between `20-90 cm`: no step/drop alarm. Move it strictly above `90 cm` for 2 confirmed frames: `ground_drop`/`ground_step` triggers stop feedback. No-target is reported separately as `down_no_target`.
8. Run `mark` or long-press touch E1: backend records a user risk point.
9. Run `path`: local walked route/risk ring buffer is printed.
10. Change `SMARTCANE_DEVICE_ID` to `cane_002`, flash again, and run `nearby`: the second cane sees the historical risk area.
11. Short-press the physical button or run `btn`: firmware uploads `voice_request`; the blind Android app enters voice interaction mode.
12. Hold the physical button for 2 seconds or run `sos`: buzzer, vibration, Serial SOS log, and backend upload as `sos`.
13. Run `scan`: root should show `0x68` or `0x69` for BMI270. Then run `imurescan`, `imu`, and `imustream on`. Move the board and confirm raw acceleration changes. For a real fall test, move the board quickly downward over a soft cushion, stop it, and lay it sideways for about 2 seconds. The buzzer alarms, no motor runs, and the backend exposes the alert to both blind and companion app roles.
