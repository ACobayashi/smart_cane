# ESP32-C5 智能盲杖软件模拟闭环

这个目录是硬件到货前的软件闭环：后端接口、ESP32-C5 模拟上传、手机定位网页、展示页。

第一版故意不依赖 FastAPI、Vue、React 等第三方包，只用 Python 标准库，避免环境安装卡住。后续迁移到 FastAPI 时，接口路径和 JSON 字段可以保持不变。

## 启动

在仓库根目录执行：

```bash
cd smart_cane_sim
export VEI_API_KEY="你的火山方舟/AI Gateway 网关访问密钥"
export AI_GATEWAY_MODEL="doubao-1.5-lite-32k"
python3 server.py
```

如果没有配置 `VEI_API_KEY`，后端会自动使用本地兜底提示函数，接口和数据库仍然可用。

浏览器打开：

```text
http://127.0.0.1:8000
```

另开一个终端，启动模拟 ESP32-C5：

```bash
cd smart_cane_sim
python3 esp32_simulator.py --allow-simulation
```

接入真实硬件后不要启动这个模拟器；它会持续写入测试风险事件，影响真实地图和路线风险统计。

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
  "event_type": "obstacle_detected",
  "risk_type": "front_obstacle",
  "level": "high",
  "direction": "front",
  "sensor": "tof_array",
  "distance_mm": 420,
  "front_mm": 420,
  "left_mm": 980,
  "right_mm": 1200,
  "down_mm": 650,
  "ground_base_mm": 650,
  "alarm_triggered": true,
  "alarm_mode": "vibration_buzzer",
  "battery": 88,
  "timestamp": "2026-07-05T12:00:01+08:00"
}
```

如果 payload 中没有 `lat/lng`，后端会自动取该 `device_id` 最近一次手机定位。

第一版建议固定这些枚举：

```text
event_type: obstacle_detected, ground_drop_detected, rough_road_detected, user_marked, sos_triggered, nearby_risk_alert
risk_type: front_obstacle, left_obstacle, right_obstacle, ground_drop, rough_road, green_channel, user_mark, sos
level: low, medium, high
direction: front, left, right, down, unknown
alarm_mode: none, vibration, buzzer, voice, vibration_buzzer
```

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

后端会优先调用 AI Gateway：

```text
AI_GATEWAY_BASE_URL=https://ai-gateway.vei.volces.com/v1
AI_GATEWAY_MODEL=doubao-1.5-lite-32k
VEI_API_KEY=你的网关密钥
```

调用失败或没有配置密钥时，会返回本地兜底提示，前端和 ESP32 上传格式不需要改。

### 查询附近共享风险

```http
GET /api/nearby-risks?device_id=cane_002&lat=31.2305&lng=121.4739&radius_m=100
```

返回结果会包含 `risk_id`、`distance_m`、`confidence`、`confirm_count`、`reported_by_count` 和 `ai_message`，用于展示多设备协同风险地图。

### 其他设备确认或更新风险

```http
POST /api/risk-reports
```

```json
{
  "risk_id": 1,
  "device_id": "cane_002",
  "action": "confirm",
  "level": "high",
  "distance_mm": 390,
  "note": "确实有台阶落差",
  "timestamp": "2026-07-05T12:05:00+08:00"
}
```

`action` 可选：

```text
confirm, dismiss, update, pass_safe
```

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
- 支持查询附近风险和多设备确认，为协同风险地图做准备。
