# API_CONTRACT

## 2026-07-24 实时导航与设备命令

- `POST /api/navigation/sessions/{session_id}/update` 返回 step、路线距离、终点距离、连续偏航/到达计数及重规划状态。
- `POST /api/navigation/sessions/{session_id}/replan` 从最近真实位置重新获取候选路线、匹配风险点并替换原 session 路线。
- `POST /api/device-commands` 创建去重的 `cancel_fall` 命令。
- `GET /api/device-commands/next?device_id=...` 由 ESP32-C5 原子领取命令。
- 规划响应统一返回 `selected_route_index`、`alternative_routes`、`route_polyline`、`route_steps` 和 `matched_risk_points`。
- 匹配风险点包含 `distance_to_route_m`、`nearest_step_index`、`nearest_road_segment_id`。

## 单位
- `front_cm` / `left_cm` / `right_cm` / `down_cm`：厘米。
- `distance_mm`：毫米，仅风险主距离。
- 经纬度：WGS84/GPS 输入；后端调用高德前转换到高德坐标。

## 风险类型
- `none`：无实时风险。
- `front_obstacle` / `left_obstacle` / `right_obstacle`：前左/右障碍，默认低风险。
- `down_obstacle`：下视有效距离 `< 20cm`，低风险，减速。
- `ground_drop` / `ground_step`：下视有效距离 `> 90cm`，中风险或更高，停止。
- `down_no_target`：下视无目标/超量程连续确认，停止确认。
- `down_sensor_unavailable`：下视传感器不可用，停止并检查设备。
- `fall_detected`：跌倒对外兼容类型，高风险。
- `fall_cancelled`：慢速跌倒取消记录，非紧急。
- `sos`：SOS，高风险。
- `voice_request`：盲杖按钮请求手机语音输入。

## 固件上传
### POST `/api/risk-events`
请求关键字段：`device_id,timestamp,lat,lng,risk_type,risk_level,direction,sensor,distance_mm,front_cm,left_cm,right_cm,down_cm,extra_json`。

### POST `/api/sensor-frames?lite=1`
请求关键字段：`device_id,lat,lng,front_cm,left_cm,right_cm,down_cm,source,manual_risk_type,manual_risk_level,manual_risk_reason,fall_detected,fall_stage,fall_confidence`。

## 后端导航
### POST `/api/navigation/voice-route`
请求：`device_id,text,current_lat,current_lng,city,coordsys,user_id?`。
响应：包含 `session_id,best_route,voice_prompt,route_count`。

### POST `/api/navigation/sessions/{session_id}/update`
请求：`lat,lng,accuracy_m?,status?`。
响应：`current_step_index,current_step,off_route_m,should_replan`。

### POST `/api/navigation/sessions/{session_id}/stop`
停止导航会话。

## 道路风险
### GET `/api/road-risk/segments`
返回道路段 polyline 和道路风险评分。

路线中的每个 step 增加：`road_segment_id,road_name,distance_m,risk_score,confidence_score,main_risk_types,risk_point_count`。

## Android 播报原则
- 新实时风险：例如“前方50厘米有障碍”。
- 历史风险：例如“前方300厘米低风险”。
- 台阶/坑洞：“下方约 X 厘米可能有台阶或坑洞，请停止。”
- 下视异常：“下方测距异常或悬空，请停止确认。”
- 下视不可用：“下视传感器不可用，请停止并检查设备。”
