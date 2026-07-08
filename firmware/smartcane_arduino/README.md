# ESP32-C5 多设备协同触控智能盲杖 Arduino 固件完善版

本目录是可直接用 Arduino IDE 打开的固件工程：`smartcane_arduino.ino`。固件实现本地避障、地面落差检测、触控握把、震动反馈、SOS、GPS 接入、联网协同、云端 AI 建议和云端语音指令演示。

重要边界：

- 避障、地面落差、SOS 都在本地运行，网络或 AI 失败不会影响安全反馈。
- ESP32-C5 不运行本地大模型或复杂 ASR；复杂语音识别放在后端云 API。
- 麦克风音频建议由手机端、网页端或后续 I2S 麦克风模块上传到后端 `/api/voice/*`。

## 硬件清单

- ESP32-C5 SensairShuttle 或兼容 ESP32 Arduino 开发板
- TCA9548A I2C 多路复用器
- 4 个 VL53L1X ToF 测距模块
- MPR121 触控模块
- PCA9685 PWM 驱动模块
- 3 个 1027 3V 震动马达：左、右、中央
- 3 路 MOS 管马达驱动电路，马达不能直接接 GPIO
- SOS 实体按键
- 3.3V 有源蜂鸣器
- GPS/GNSS 串口模块，例如 ATGM336H、NEO-6M、NEO-M8N
- 可选：手机端或 I2S 麦克风模块，用于上传音频到后端做云端语音识别

## 推荐接线

| ESP32-C5 / 模块 | 连接 |
| --- | --- |
| ESP32-C5 3V3 / GND | 所有 I2C 模块电源与地，注意共地 |
| ESP32-C5 `I2C_SDA_PIN` / `I2C_SCL_PIN` | TCA9548A、MPR121、PCA9685 的 SDA/SCL 并联 |
| TCA9548A CH0 | 前方 VL53L1X |
| TCA9548A CH1 | 左侧 VL53L1X |
| TCA9548A CH2 | 右侧 VL53L1X |
| TCA9548A CH3 | 下视 VL53L1X |
| PCA9685 CH0 | 左震动马达 MOS 管驱动输入 |
| PCA9685 CH1 | 右震动马达 MOS 管驱动输入 |
| PCA9685 CH2 | 中央震动马达 MOS 管驱动输入 |
| `SOS_BUTTON_PIN` | SOS 按键，默认低电平按下并启用内部上拉 |
| `BUZZER_PIN` | 3.3V 有源蜂鸣器控制脚 |
| GPS TX | ESP32 `GPS_RX_PIN` |
| GPS RX | ESP32 `GPS_TX_PIN`，多数只读定位场景可不接 |

## Arduino IDE 依赖库

在 Arduino IDE Library Manager 中安装：

- `Adafruit_MPR121`
- `Adafruit_PWMServoDriver`
- `VL53L1X`，推荐 Pololu 的 VL53L1X 库
- `ArduinoJson`
- `TinyGPSPlus`
- `WiFi`，ESP32 Arduino core 自带
- `HTTPClient`，ESP32 Arduino core 自带

## 关键配置

打开 `config.h` 修改：

```cpp
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define SERVER_BASE_URL "http://192.168.1.100:8000"
#define DEVICE_ID "cane_001"
```

`SERVER_BASE_URL` 必须是 ESP32 能访问到的电脑局域网 IP 或服务器地址。不要写 `127.0.0.1`。

GPS 配置：

```cpp
#define USE_GPS_MODULE 1
#define GPS_MOCK_FALLBACK 1
static const uint8_t GPS_RX_PIN = 18;
static const uint8_t GPS_TX_PIN = 19;
static const uint32_t GPS_BAUD = 9600;
```

如果室内没有 GPS 定位，`GPS_MOCK_FALLBACK` 会使用 `MOCK_LAT/MOCK_LNG` 保证演示不中断。

## 触控映射

- electrode 0 轻触：查询当前风险，并请求后端 AI 建议
- electrode 0 双击：发送云端语音文本指令演示
- electrode 1 长按：上传 `user_mark`
- electrode 2 轻触：重复最近一次震动模式
- electrode 3 轻触：本地模式 / 联网模式切换
- electrode 4 轻触：左侧 / 上一项
- electrode 5 轻触：右侧 / 下一项

串口触控模拟：

- `0` 到 `5`：轻触 electrode 0 到 5
- `A` 到 `F`：长按 electrode 0 到 5
- `a` 到 `f`：双击 electrode 0 到 5
- `U`：上传 `user_mark`
- `R`：重复最近震动
- `M`：切换模式

## 功能演示

1. 启动后串口显示四路 ToF 距离、风险状态、GPS/mock 位置。
2. 前方障碍触发中央震动；高风险触发中央强震和蜂鸣器短鸣。
3. 左右距离比较后，左/右马达提示绕行方向；两侧都窄则三路急震提示停止。
4. 下视距离变大触发地面落差，上传 `ground_drop`。
5. GPS 有定位时上传真实位置；无定位时使用 mock 位置演示。
6. 触摸 electrode 1 长按 1 秒，上传人工风险点。
7. 触摸 electrode 0，串口输出 AI 风险建议。
8. 双击 electrode 0，演示云端语音文本指令识别。
9. 修改 `DEVICE_ID` 为 `cane_002`，第二根盲杖可读取第一根盲杖留下的历史风险。
10. 长按 SOS 按键 2 秒，触发蜂鸣器、三路震动、串口 SOS 输出和后端上传。

