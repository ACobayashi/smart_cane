# 本地提示事件实时播报更新

## 目标

手机只播报盲杖已经实际执行过蜂鸣器或马达提示的新事件，不再从设备当前状态、普通传感器帧、历史风险点或 AI 风险分析中推断实时硬件语音。

## 固件

- 吸收 PR #21 的本地提示事件协议。
- 每次物理提示执行后上传 `smartcane.local_cue.v1` 事件。
- 普通提示使用设备唯一的 `cue_id`；重复提示带 `cue_repeat=true`。
- 正式跌倒使用 `fall_event_id` 作为 `cue_id`，候选跌倒不产生本地提示事件。
- 串口输出 `[CUE_EVENT]`，用于核对物理提示和网络事件是否对应。

## 后端

- 解析对象或字符串形式的 `extra_json`。
- 将 cue 字段写入 `risk_events` 的独立列，并按 `device_id + cue_id` 建唯一索引。
- 重复 cue 返回原事件，不生成新的数据库记录。
- 新增接口：
  - `GET /api/cues/latest`
  - `GET /api/cues/since`
  - `GET /api/cues/stream`（SSE）
- SSE 只发送 `is_local_cue=true` 的事件，约每 150 毫秒检查新事件。
- 普通 cue 只发给用户端；正式跌倒也可发给陪护端。
- `speech.shouldSpeak` 只对 3 秒内、非重复、字段完整的新 cue 为真。

## Android

- `device_state` 每秒轮询只更新在线状态、距离和跌倒候选 UI，不触发 TTS。
- 删除普通风险事件轮询触发语音的路径。
- 首次连接先读取最新 cue ID 作为基线，不补播应用启动前的旧事件。
- 持续连接 `/api/cues/stream`，并在手机端再次检查：
  - 当前盲杖设备 ID
  - `eventKind=local_cue`
  - cue ID 非空且未播报
  - `cue.repeat=false`
  - 服务端时间差不超过 3 秒
  - 正式跌倒的 cue ID 与 `fall_event_id` 一致
- 新 cue 使用 `QUEUE_FLUSH` 立即播报；导航和历史风险点保留各自原有播报链路。

## 验证

- 后端完整链路测试覆盖 cue 解析、去重、repeat 静音、普通事件隔离和正式跌倒门禁。
- Android 单测覆盖 cue ID 去重、设备匹配、3 秒新鲜度、repeat 静音和正式跌倒门禁。
