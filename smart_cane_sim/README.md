# ESP32-C5 智能盲杖软件模拟闭环

这个目录是硬件到货前的软件闭环：后端接口、ESP32-C5 模拟上传、手机定位网页、展示页。

第一版故意不依赖 FastAPI、Vue、React 等第三方包，只用 Python 标准库，避免环境安装卡住。后续迁移到 FastAPI 时，接口路径和 JSON 字段可以保持不变。

## 启动

在仓库根目录执行：

```bash
cd smart_cane_sim
python3 server.py
```

浏览器打开：

```text
http://127.0.0.1:8000
```

另开一个终端，启动模拟 ESP32-C5：

```bash
cd smart_cane_sim
python3 esp32_simulator.py
```

手机需要和电脑在同一个网络下，打开：

```text
http://电脑局域网IP:8000
```

手机网页点击“上传当前位置”或“连续上传”，后端会把同一 `device_id` 的最新手机位置绑定到之后的风险事件上。

注意：手机浏览器通过 `http://局域网IP:8000` 访问时，真实 GPS 权限可能因为不是 HTTPS 被拦截。遇到这种情况，先用网页里的“上传手动位置”完成比赛演示闭环；正式部署到 HTTPS 后再启用真实定位。

## 当前接口

### 健康检查

```http
GET /api/health
```

### 手机上传位置

```http
POST /api/locations
```

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "accuracy_m": 12,
  "source": "phone",
  "timestamp": "2026-07-05T12:00:00+08:00"
}
```

### 查询最新位置

```http
GET /api/locations/latest?device_id=cane_001
```

### ESP32-C5 上传风险事件

```http
POST /api/risk-events
```

```json
{
  "device_id": "cane_001",
  "risk_type": "front_obstacle",
  "level": "high",
  "direction": "front",
  "sensor": "tof_front",
  "distance_mm": 420,
  "battery": 88,
  "timestamp": "2026-07-05T12:00:01+08:00"
}
```

如果 payload 中没有 `lat/lng`，后端会自动取该 `device_id` 最近一次手机定位。

### 前端查询风险点

```http
GET /api/risk-events?device_id=cane_001&limit=100
```

### AI 提示接口

```http
POST /api/ai-advice
```

```json
{
  "risk_type": "ground_drop",
  "level": "high",
  "direction": "down",
  "distance_mm": 1200
}
```

当前 `server.py` 的 `ai_advice()` 是模拟大模型函数。接入真实大模型时，把这个函数替换成模型 API 调用即可，前端和 ESP32 上传格式不需要改。

## 软等待硬件设备到货

到货前：

- 用 `esp32_simulator.py` 模拟 ESP32-C5。
- 用手机网页上传 GPS。
- 用网页展示风险点。

等设备全部到齐之后我们

- 保留 `server.py` 和网页。
- 用 ESP32-C5 Arduino 代码替换 `esp32_simulator.py`。
- ESP32-C5 只需要按 `/api/risk-events` 的 JSON 格式上传。

## MVP 完成标准

- 后端能保存手机位置。
- 模拟器能连续上传风险事件。
- 风险事件能自动绑定最近手机位置。
- 前端能看到最新风险、提示语和点位。
- 后续可以把 `ai_advice()` 换成真实云端大模型。
