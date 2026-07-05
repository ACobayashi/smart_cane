# ESP32-C5 Smart Cane Prototype

这是一个面向物联网竞赛的 ESP32-C5 智能盲杖原型仓库。当前目标是先跑通软件闭环：

```text
ESP32-C5 设备端 -> 后端接口 -> SQLite 存储 -> 网页前端展示
```

硬件未完全到货前，仓库同时提供两种模拟方式：

- 电脑端模拟 ESP32-C5 上传风险事件。
- ESP32-C5 真板模拟传感器距离并上传风险事件。

## 文件树

```text
.
├── arduino/
│   └── SmartCane_ESP32C5/
│       ├── SmartCane_ESP32C5.ino   # 硬件端/设备端 Arduino 主程序
│       ├── config.h                # Wi-Fi、后端地址、设备 ID、阈值配置
│       └── README.md               # Arduino 烧录和调试说明
├── smart_cane_sim/
│   ├── server.py                   # 后端代码：HTTP API + SQLite 存储
│   ├── esp32_simulator.py          # 电脑端 ESP32-C5 模拟上传脚本
│   ├── README.md                   # 后端/前端模拟闭环说明
│   └── web/
│       ├── index.html              # 前端页面
│       ├── styles.css              # 前端样式
│       └── app.js                  # 前端交互逻辑
├── ESP32-C5多设备协同触控智能盲杖采购清单.docx
├── ESP32-C5触控协同智能盲杖_Arduino配置与代码框架.docx
├── 乐鑫赛道.pdf
└── 4928bfc9-c8a1-484f-848e-9dcb9f15ba7a.docx
```

## 两个代码目录的关系

`arduino/SmartCane_ESP32C5` 是硬件端代码，未来烧录到 ESP32-C5 板子里。它负责读取传感器、判断风险、连接 Wi-Fi，并上传 JSON 风险事件。

`smart_cane_sim` 是后端和前端模拟闭环，当前跑在电脑上。它负责接收风险事件、接收手机定位、绑定风险点位置、保存数据，并在网页中展示。

两端通过 HTTP JSON 接口连接：

```text
arduino/SmartCane_ESP32C5
        |
        | POST /api/risk-events
        v
smart_cane_sim/server.py
        |
        v
smart_cane_sim/web
```

## 当前接口

设备端上传风险：

```http
POST /api/risk-events
```

示例：

```json
{
  "device_id": "cane_001",
  "risk_type": "front_obstacle",
  "level": "high",
  "direction": "front",
  "sensor": "tof_front",
  "distance_mm": 420,
  "battery": 88
}
```

手机或网页上传位置：

```http
POST /api/locations
```

后端会把同一个 `device_id` 的最近位置绑定到之后上传的风险事件上。

## 本地运行

启动后端和前端：

```bash
cd smart_cane_sim
python3 server.py
```

浏览器打开：

```text
http://127.0.0.1:8000
```

没有 ESP32-C5 时，可以用电脑端模拟器上传风险：

```bash
cd smart_cane_sim
python3 esp32_simulator.py
```

有 ESP32-C5 板子时，打开 Arduino IDE，烧录：

```text
arduino/SmartCane_ESP32C5/SmartCane_ESP32C5.ino
```

先修改：

```text
arduino/SmartCane_ESP32C5/config.h
```

其中 `SERVER_BASE_URL` 必须是电脑局域网 IP，例如：

```cpp
const char *SERVER_BASE_URL = "http://10.130.255.68:8000";
```

不能写 `127.0.0.1`，因为对 ESP32-C5 来说那是它自己。

## 后续扩展

这个结构已经给多设备协同和云端部署留了余地：

- `device_id` 用来区分多根盲杖。
- `lat/lng` 用来形成共享风险地图。
- 后续可增加 `/api/nearby-risks`，让一根盲杖查询其他设备标记过的附近风险。
- 云端部署时只需要把 `SERVER_BASE_URL` 改成云服务器域名。

下一阶段建议补：

- 真实 VL53L1X 测距读取。
- MPR121 触控握把。
- PCA9685 + MOS 管震动反馈。
- 真实大模型 API 调用，替换当前 `server.py` 中的模拟提示函数。
