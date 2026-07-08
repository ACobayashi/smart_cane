# Smart Cane FastAPI 后端完善版

本目录提供智能盲杖后端，使用 FastAPI + SQLite 保存多设备风险事件、GPS 位置、附近风险统计，并可选接入云端大模型和语音识别。

安全原则：

- 不要把真实 API Key 写进代码、README 或 Git。
- 把 `backend/.env.example` 复制为 `backend/.env`，只在本机填写真实 Key。
- 如果 Key 曾经发到聊天、截图或仓库里，请立刻到平台后台撤销并重新生成。

## 安装与运行

```bash
cd backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

macOS/Linux：

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn main:app --host 0.0.0.0 --port 8000
```

## 环境变量

`LLM_PROVIDER` 可选：

- `ark`：火山方舟 / 豆包，使用 OpenAI-compatible Chat Completions。
- `openai`：OpenAI 官方 API。

语音识别使用：

- `STT_PROVIDER=openai`
- `OPENAI_STT_MODEL=whisper-1`

如果供应商没有配置 Key，AI 建议接口会返回规则兜底建议；语音转写接口会返回 503，地图和事件接口不受影响。

## 接口

### GET `/api/health`

健康检查。

### GET `/api/ai/status`

查看 LLM/STT 是否配置成功，不会返回密钥。

### POST `/api/events`

上传风险事件。

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "ground_drop",
  "risk_level": "high",
  "front_cm": 180,
  "left_cm": 130,
  "right_cm": 120,
  "down_cm": 95,
  "extra_json": "source=auto_detected"
}
```

### GET `/api/events`

查看全部风险事件，方便演示。

### POST `/api/locations`

上传设备 GPS 或 mock 位置。

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "source": "gps",
  "accuracy_m": 8.5,
  "satellite_count": 9
}
```

### GET `/api/locations/latest?device_id=cane_001`

查看某个设备最近位置。

### GET `/api/risks/nearby?lat=&lng=&radius=`

查询附近风险统计。`radius` 单位为米，默认 80。

返回：

```json
{
  "risk_count": 3,
  "high_count": 2,
  "medium_count": 1,
  "max_level": "high",
  "recent_events": []
}
```

### POST `/api/ai/advice`

根据实时距离、历史风险和当前位置，调用云端大模型生成一句短提示。没有 Key 时返回规则兜底。

```json
{
  "device_id": "cane_001",
  "lat": 31.2304,
  "lng": 121.4737,
  "risk_type": "front_obstacle",
  "risk_level": "high",
  "front_cm": 45,
  "left_cm": 120,
  "right_cm": 50,
  "down_cm": 45
}
```

### POST `/api/voice/transcribe`

上传音频文件并调用云端语音转写。适合手机端、网页端或后续 I2S 麦克风模块上传音频。

```bash
curl -X POST http://127.0.0.1:8000/api/voice/transcribe ^
  -F "file=@demo.wav" ^
  -F "language=zh"
```

### POST `/api/voice/text-command`

把已转成文本的语音命令交给大模型做意图识别。固件的 electrode 0 双击演示会调用这个接口。

### POST `/api/voice/command`

一步完成音频转写 + 命令识别。

## 多设备协同演示

1. 启动后端。
2. `cane_001` 上传 `user_mark`、`ground_drop` 或 `sos`。
3. 查看 `/api/events`，确认风险点保存。
4. 修改固件 `DEVICE_ID` 为 `cane_002`。
5. 第二根盲杖启动后上传当前位置并请求 `/api/risks/nearby`。
6. 固件本地风险逻辑融合实时距离和历史风险，AI 建议接口可进一步生成一句自然语言提示。

后续可接手机定位、高德地图或 Web 前端展示；当前后端只做 SQLite 存储和经纬度近似距离计算。

