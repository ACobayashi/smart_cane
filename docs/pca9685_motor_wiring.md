# PCA9685 motor wiring checklist

Use this for the replacement blue PCA9685 board shown in the bench photo.

## Safety first

- Power everything off before moving motor wires.
- Hold the plastic plug, heat-shrink, or insulated joint. Do not pull the thin motor wires directly.
- Keep the ESP32/TCA logic power separate from the motor power rail.
- Common ground is required: ESP32 GND, TCA GND, PCA9685 GND, and motor battery GND must connect together.

## I2C and logic wiring

| PCA9685 pin | Connect to |
| --- | --- |
| `SDA` | TCA9548A channel `CH6` `SDA` |
| `SCL` | TCA9548A channel `CH6` `SCL` |
| `VCC` | ESP32/TCA `3.3V` logic power |
| `GND` | ESP32/TCA `GND` |
| `OE` | Leave open first; if `0x40` is detected but outputs stay disabled, tie `OE` to `GND` |

The board address should be `0x40`.

## Motor power

The green screw terminal powers the motor rail only:

| Green terminal | Connect to |
| --- | --- |
| `V+` | 3V or 3.7V vibration motor battery positive |
| `GND` | Battery negative and common ground |

Do not feed the ESP32 logic rail from motor `V+`.

## Motor channel order

The bottom three-pin headers are labeled by row:

| Row | Meaning |
| --- | --- |
| Yellow/top row | `PWM` / signal |
| Red/middle row | `V+` |
| Black/bottom row | `GND` |

Current firmware bench mode expects one physical motor only:

| Motor | PCA9685 channel |
| --- | --- |
| Single vibration motor | `CH0` |

`m1`, `m2`, `m3`, left/right/center, obstacle, ground-drop, and SOS cues all run through `CH0`.
The firmware encodes them as different pulse patterns so you can test safely with one mounted motor.
After the other motors are wired, change `SMARTCANE_VIB_MOTOR_COUNT` in `firmware/smartcane_arduino/config.h` back to `3`.

If the motor plug has three wires, keep its original orientation:

- red to `V+`
- black/brown to `GND`
- white/orange/yellow signal wire to `PWM`

If the vibration motor is a bare two-wire DC motor, do not connect it directly to `PWM/V+/GND`. Use a MOSFET/transistor motor driver per motor, with PCA9685 `PWM` connected to the driver input.

## Serial test commands

Open Serial Monitor at `115200` baud after flashing:

```text
scan
pca
m1
mstop
m2
mstop
m3
mstop
```

Expected behavior:

- `scan` or `pca` finds PCA9685 at `0x40` on TCA channel `6`.
- `m1` vibrates the CH0 motor once.
- `m2` vibrates the same CH0 motor twice.
- `m3` vibrates the same CH0 motor three times.
- `mstop` stops CH0 immediately.

If a motor runs continuously, power off immediately and recheck the motor power/driver wiring.
