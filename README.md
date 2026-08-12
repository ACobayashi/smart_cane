# ESP32-C5 多设备协同智能盲杖

本仓库现已将 **Arduino IDE / Arduino Framework** 作为主要固件开发路径。

本系统实现了一套可实际运行的 **ESP32-C5 多设备协同智能盲杖**，主要功能包括：

* 本地障碍物与地面落差风险检测；
* 振动与蜂鸣器反馈；
* 触摸手柄与 SOS 交互；
* 行进路线记录；
* 后端风险点上传；
* 附近历史风险查询，实现多设备协同地图；
* 后端轻量级深度风险评分；
* 可选的云端大模型（LLM）建议。

本地安全功能始终采用**基于规则的判断逻辑**，并支持**离线运行**。网络、深度风险评分和大模型建议仅用于增强手机端和后端的反馈能力，**不会替代本地障碍物检测与避障逻辑**。

更换后的 PCA9685 电机驱动板接线方式请参见：

```text
docs/pca9685_motor_wiring.md
```

**私人参考 PDF 文件和 API Key 禁止提交或上传至仓库。**

---

## 仓库结构

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
  Android Jetpack Compose 前端

docs/
  api_contract.md
```

---

## 硬件组成

| 模块                                               | 作用                                                 |
| ------------------------------------------------ | -------------------------------------------------- |
| ESP32-C5 SensairShuttle 或兼容 ESP32-C5 Arduino 开发板 | 系统主控制器                                             |
| TCA9548A                                         | I2C 多路复用器，用于连接 4 个地址相同的 VL53L1X ToF 传感器            |
| 4 × VL53L1X                                      | 分别负责前方、左侧、右侧和下方距离检测                                |
| MPR121 / HW-017                                  | 电容式触摸手柄                                            |
| PCA9685 PWM/Servo Shield                         | 蓝色电机 PWM 驱动板，连接至 TCA `CH6`，地址为 `0x40`              |
| 1 × 1027 3V 振动电机                                 | 当前台架测试使用单振动电机，通过 PCA9685 `CH0` 控制；后续固件可重新切换为三个振动电机 |
| 有源蜂鸣器                                            | 用于高风险、地面落差和 SOS 报警                                 |
| 物理按键                                             | 短按请求 Android 端启动语音输入；长按触发 SOS                      |

根据当前 Arduino 测试截图，推荐使用以下接线方式：

| 硬件          | ESP32-C5 / TCA / PCA9685 接线                   |
| ----------- | --------------------------------------------- |
| I2C SDA     | `GPIO2`                                       |
| I2C SCL     | `GPIO3`                                       |
| TCA9548A 地址 | `0x70`                                        |
| 前方 VL53L1X  | TCA `CH2`                                     |
| 左侧 VL53L1X  | TCA `CH3`                                     |
| 右侧 VL53L1X  | TCA `CH4`                                     |
| 下方 VL53L1X  | TCA `CH5`                                     |
| MPR121      | TCA `CH7`，地址 `0x5A`                           |
| PCA9685     | TCA `CH6`，地址 `0x40`                           |
| 当前振动电机      | PCA9685 `CH0` PWM/SIG；红线接 `V+`，黑色/棕色线接 `GND`  |
| 蜂鸣器         | `GPIO4`                                       |
| 物理按键        | `GPIO5`，低电平有效；短按触发 `voice_request`，长按触发 `sos` |

如果之后重新将 ToF 传感器接回 `CH0/CH1/CH2/CH3`，只需要修改：

```text
firmware/smartcane_arduino/config.h
```

### 独立盲杖供电说明

* **ESP32-C5 / SensairShuttle**：开发阶段可使用 USB-C 5V 供电；如果开发板支持，也可以使用板载 3.7V 锂电池接口供电。
* **PCA9685 蓝色电机驱动板**：电机 `V+` 可以直接使用目前已经为振动电机连接的独立 3.7V 电池。
* ESP32 的 `GND`、PCA9685 逻辑 `GND` 以及电机电池 `GND` 必须**共地**。
* PCA9685 的逻辑电源 `VCC` 应连接至 ESP32 的 **3.3V 逻辑电源**。
* **禁止使用电机 `V+` 电源轨给 ESP32 逻辑电路供电。**
* 当前台架测试中的振动电机连接在蓝色 PCA9685 的 `0` 号位置，即 `CH0`。当前固件通过该单个电机的不同脉冲模式表示不同提示信息。

---

## Arduino 依赖库

在 Arduino IDE 的 **Library Manager（库管理器）** 中安装：

* `Adafruit MPR121`
* `Adafruit PWM Servo Driver Library`
* `VL53L1X` by Pololu
* `ArduinoJson`

以下库由 ESP32 Arduino 开发板包自带，无需单独安装：

* `WiFi`
* `HTTPClient`

---

## 固件配置与运行

使用 Arduino IDE 打开：

```text
D:\smartcane\firmware\smartcane_arduino\smartcane_arduino.ino
```

在 Arduino IDE 中：

1. 开发板选择 `ESP32C5 Dev Module`。
2. 选择对应的串口，例如 `COM3`。
3. 串口监视器波特率设置为 `115200 baud`。
4. 编译并上传程序。

设备 ID、Wi-Fi、后端 URL、风险阈值、GPIO、I2C 通道以及模拟路线数据均在以下文件中配置：

```text
firmware/smartcane_arduino/config.h
```

本地测试时，应填写电脑在局域网中的 IP 地址。

如果智能盲杖连接手机热点，则需要确保：

* PC 连接同一个手机热点；
* ESP32-C5 连接同一个手机热点；
* Android 测试手机处于同一网络；
* 后端地址填写运行后端电脑的热点/局域网 IPv4 地址。

例如：

```cpp
#define SMARTCANE_SERVER_BASE_URL "http://118.31.221.165:8016"
```

**ESP32 端不要使用 `127.0.0.1`。**

---

## 后端配置

在 PowerShell 中执行：

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8016
```

### 健康检查

```text
http://118.31.221.165:8016/api/health
```

### 主要业务接口

* `POST /api/locations`：上传路线位置点
* `GET /api/locations/history?device_id=cane_001`：查询指定设备历史路线
* `POST /api/risk-events`：上传风险点
* `GET /api/risk-events`：查询风险事件
* `GET /api/risks/nearby?lat=31.2304&lng=121.4737&radius=80`：查询附近历史风险
* `POST /api/ai/deep-risk`：进行深度风险评分
* `POST /api/ai/advice`：获取 AI 风险处理建议

### Android 前端兼容接口

* `GET /status`
* `GET /devices`
* `GET /events/latest`
* `POST /sos`
* `POST /telemetry`

云端大模型和语音服务均为**可选功能**。

相关 API Key 只能存放在：

```text
backend/.env
```

**禁止将真实密钥提交至 Git 仓库。**

---

## 系统闭环测试流程

1. 启动后端服务。

2. 将 Arduino 固件中的设备 ID 设置为：

```text
SMARTCANE_DEVICE_ID="cane_001"
```

然后烧录 ESP32-C5。

3. 在串口监视器中执行：

```text
status
```

或：

```text
read
```

打印一次当前 ToF 距离和风险状态快照。

4. 在盲杖前方放置障碍物。

固件每隔 `500 ms` 进行一次采样。

检测到新的风险状态后：

* 串口输出一次发生变化的风险事件；
* 通过振动提示用户减速；
* 根据左右两侧空间情况提示向左或向右绕行。

单次距离障碍会作为**低风险地图点**记录。

5. 保持盲杖位置不变，并维持相同障碍状态。

对于**同一地点、同一类型的风险**，系统不会持续重复：

* 串口输出；
* 振动提醒；
* 上传后端。

6. 增大左侧或右侧可通行空间，然后：

* 清除当前风险后再次触发；
* 或移动到另一个位置网格。

系统会根据左右两侧空间选择较安全的绕行方向，并通过对应振动提示用户，同时生成新的风险记录。

7. 测试下方 ToF 地面检测：

* 将下方距离降低到 **20 cm 以下**，模拟较近的路沿、凸起等障碍。固件会将其作为低风险 `down_obstacle` 上传。
* 下方距离保持在 **20–90 cm** 时，认为地面正常，不产生台阶/落差报警。
* 将有效的下方距离连续两帧保持为**严格大于 90 cm**，模拟坑洞或明显地面落差。固件会上传 `ground_drop`。
* 当传感器没有检测到有效目标时，单独记录为 `down_no_target`。

8. 长按触摸电极 `E1`，或者在串口中执行：

```text
mark
```

后端会在当前路线位置记录一个：

```text
user_mark
```

风险点。

9. 在串口中执行：

```text
path
```

输出本地路线 / 风险点环形缓冲区数据。

10. 将设备 ID 修改为：

```text
SMARTCANE_DEVICE_ID="cane_002"
```

重新烧录第二台设备，然后执行：

```text
nearby
```

第二台盲杖会获取附近的历史风险统计数据，并将历史风险信息与本地检测结果进行融合，实现**多设备协同风险地图**。

11. 短按物理按键，或者在串口中执行：

```text
btn
```

盲杖会上传：

```text
voice_request
```

盲人用户 Android App 随后进入语音交互模式。

**陪护端 App 不接收普通的 `voice_request` 请求。**

12. 长按物理按键 **2 秒**，或者执行：

```text
sos
```

盲杖将执行：

* 振动；
* 蜂鸣报警；
* 串口输出 SOS 信息；
* 上传 `sos` 事件。

后端会区分：

```text
sos
```

与：

```text
fall_detected
```

两种事件。

13. 测试跌倒检测时，将搭载 BMI270 的开发板轻轻放倒或倾斜到软垫上，并保持侧倾状态一段时间。

检测到跌倒后：

* 盲杖仅使用蜂鸣器报警；
* 上传 `fall_detected`；
* 后端同时向**盲人用户端**和**陪护端**提供该事件。

完整串口命令列表参见：

```text
firmware/smartcane_arduino/README.md
```

---

## Android 前端

使用 Android Studio 打开：

```text
D:\smartcane\frontend\SmartCane
```

App 的后端地址配置文件为：

```text
frontend\SmartCane\local.properties
```

### 真机测试

如果 Android 手机与电脑处于同一 Wi-Fi / 热点网络，应填写电脑的 IPv4 地址，例如：

```properties
BACKEND_BASE_URL=http://118.31.221.165:8016
```

### Android Emulator 模拟器测试

例如：

```kotlin
const val BASE_URL = "http://118.31.221.165:8016"
```
