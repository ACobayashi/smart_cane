# SmartCane_ESP32C5 Arduino 工程

这是硬件未完全到货前可用的 ESP32-C5 Arduino 版本。它先用模拟距离数据生成风险事件，并上传到本地后端：

```text
POST /api/risk-events
```

后续传感器到货后，把模拟距离读取替换成真实 `VL53L1X` 读取即可，上传接口不用变。

## 使用步骤

1. 启动电脑端后端：

   ```bash
   cd /Users/akobayashi/Documents/IoT/smart_cane_sim
   python3 server.py
   ```

2. 打开 `config.h`，修改：

   ```cpp
   const char *WIFI_SSID = "你的WiFi";
   const char *WIFI_PASSWORD = "你的密码";
   const char *SERVER_BASE_URL = "http://你的电脑局域网IP:8000";
   ```

   注意：ESP32-C5 不能访问电脑自己的 `127.0.0.1`，必须填电脑在同一 Wi-Fi 下的局域网 IP。

3. Arduino IDE 选择：

   ```text
   Board: ESP32C5 Dev Module
   Port: 对应 USB 串口
   Serial Monitor: 115200 baud
   ```

4. 上传 `SmartCane_ESP32C5.ino`。

5. 打开串口监视器，看是否出现：

   ```text
   Wi-Fi connected
   POST http://.../api/risk-events status=201
   ```

6. 打开网页：

   ```text
   http://127.0.0.1:8000
   ```

   应该能看到 ESP32-C5 上传的风险事件。

## 现在这个版本做了什么

- 模拟前、左、右、下四路距离。
- 根据阈值判断 `front_obstacle`、`left_obstacle`、`right_obstacle`、`ground_drop`。
- 每 2 秒上传一次当前风险。
- 上传字段和 Python 模拟器保持一致。

## 后续硬件替换点

当前 `.ino` 里这一段是替换点：

```cpp
#if USE_MOCK_SENSORS
latestDistances = readMockDistances();
#else
latestDistances = readMockDistances();
#endif
```

传感器到了以后：

- 安装 `Pololu VL53L1X` 库。
- 添加 TCA9548A 通道选择函数。
- 用真实 ToF 读数构造 `Distances`。
- 保留 `evaluateRisk()` 和 `postRiskEvent()`。

也就是说，云端和前端不用因为硬件到货而重写。
