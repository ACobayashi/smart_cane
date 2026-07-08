# ESP32-C5 多设备协同触控智能盲杖系统完善版

本仓库实现一个可落地演示的智能盲杖原型：

```text
ESP32-C5 Arduino 固件 -> FastAPI 后端 -> SQLite 风险地图 -> 云端 LLM / 语音识别
```

当前版本支持本地避障、地面落差检测、GPS 接入、触控握把、SOS、联网风险上传、多设备协同风险地图、云端大模型风险建议和云端语音识别接口。


## 目录结构

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
  gps_location.*
  risk_logic.*
  network_client.*
  data_model.h
  README.md

backend/
  main.py
  requirements.txt
  .env.example
  README.md
```

## 后端快速运行

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

在 `backend/.env` 里选择：

```text
LLM_PROVIDER=ark
```

或：

```text
LLM_PROVIDER=openai
```

然后填写对应平台的真实 Key。不要把真实 Key 写入 Git。

检查：

```bash
curl http://127.0.0.1:8000/api/health
curl http://127.0.0.1:8000/api/ai/status
```

## Arduino 烧录

1. Arduino IDE 打开 `firmware/smartcane_arduino/smartcane_arduino.ino`。
2. 安装库：`Adafruit_MPR121`、`Adafruit_PWMServoDriver`、`VL53L1X`、`ArduinoJson`、`TinyGPSPlus`。
3. 修改 `config.h` 中的 Wi-Fi、服务器地址、`DEVICE_ID`、GPS 引脚、阈值。
4. 选择 ESP32-C5 开发板并上传。
5. 打开 115200 串口监视器。

## 推荐硬件

- ESP32-C5 SensairShuttle 或兼容 ESP32 Arduino 板
- TCA9548A
- 4 个 VL53L1X
- MPR121
- PCA9685
- 3 个震动马达 + MOS 管驱动
- SOS 按键
- 有源蜂鸣器
- 串口 GPS/GNSS 模块
- 可选手机端或 I2S 麦克风，用于音频上传到后端做云端 ASR

## 演示流程

1. 启动后端，确认 `/api/health` 正常。
2. 固件启动后串口显示距离、GPS/mock 位置、历史风险。
3. 前方障碍触发震动和蜂鸣器。
4. 地面落差触发强警报并上传 `ground_drop`。
5. 触摸 electrode 1 长按上传 `user_mark`。
6. 触摸 electrode 0 获取云端 AI 风险建议。
7. 双击 electrode 0 演示云端语音文本命令识别。
8. 用 `/api/voice/transcribe` 上传音频，演示复杂语音识别。
9. 修改 `DEVICE_ID` 为第二根盲杖，验证附近历史风险融合。
10. 长按 SOS 按键 2 秒，上传 `sos`。

详细接线、接口和演示命令见：

- `firmware/smartcane_arduino/README.md`
- `backend/README.md`

