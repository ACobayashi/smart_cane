# CHANGELOG_FULL_CHAIN

## 2026-07-24 主流程闭环补充

- 导航偏航改为连续三次超过 25 米；到达改为连续三次进入 20 米范围。
- 新增原 navigation session 内高德多路线重规划、风险点重新匹配和路线替换。
- step 切换自动完成旧 `road_traversal` 并创建新 traversal；停止、到达、超时分别形成 cancelled、completed、incomplete。
- 安全路线严格限制在最短路线 130% 内，并按风险优先级排序。
- Android 新增真实导航地图；高德路线和风险 Marker 为独立图层，删除风险点互连假路线。
- Android 增加安全/距离优先、Service 状态回传、偏航重规划、step 推进与到达自动停止。
- 新增统一 TTS 优先级队列、12 秒去重和高优先级抢占。
- 新增 Android→后端命令队列→ESP32-C5 的慢速跌倒取消闭环。
- 生产用户未绑定真实设备时禁止发送导航、SOS、语音或取消请求。

日期：2026-07-23

## 本次目标
修复下视台阶/坑洞检测、优化快慢跌倒检测、补齐导航会话、加入道路级风险量化，并校准固件/后端/Android 字段契约。

## 已完成
- 下视规则统一：台阶只由固件结合 BMI270 姿态、稳定 baseline 和连续帧的前后落差状态机确认；后端和深度模型不再用绝对 `down_cm > 90` 判断台阶。
- ToF 滤波修复：首次有效读数直接初始化 EMA；下视判断使用 `downRawCm` 快速通道；超时不再当作 390cm 深坑；无目标/超量程单独标记 `down_no_target`。
- 下视单路失效会产生 `down_sensor_unavailable`，不会因其他三路正常而吞掉。
- 新增真正运行的 Android `NavigationLocationService`：导航会话创建后启动，2 秒上传真实非 mock 位置，持久化 session，支持 `START_STICKY` 重建并避免重复监听器。
- Manifest 补齐通知权限及 location 类型前台服务；导航 API 移除默认 `cane_001` 参数，调用方必须传当前绑定设备。
- BMI270 快速跌倒保留运动事件+姿态+静止确认；慢速跌倒增加使用姿态预稳定、倾斜过程、横躺静止、取消窗口。
- 慢速跌倒 12 秒取消窗口：短按按钮或触摸 E2 可上传 `fall_cancelled`，未取消再向后端上传高风险 `fall_detected`。
- 后端普通分析、`/api/ai/deep-risk`、`/api/ai/advice` 的下视阈值统一为 20/90。
- 新增 `navigation_sessions`，语音路线规划会创建真实会话并保存 polyline/steps/current_step。
- 新增道路级表：`road_segments`、`road_risk_observations`、`road_traversals`、`road_risk_scores`。
- 路线 step 返回道路风险字段：`road_name`、`distance_m`、`risk_score`、`confidence_score`、`main_risk_types`、`risk_point_count`。
- Android `postVoiceRoute()` 不再写死 `cane_001`，使用当前绑定设备 ID，并发送当前手机定位。
- Android 播报补齐 `down_no_target`、`down_sensor_unavailable`、台阶坑洞文案，保留 10 秒去重和跌倒优先级。
- Wi-Fi SSID/密码从固件公开硬编码改为可编译注入的空默认值。

## 保留兼容
- 旧 `/api/risk-events`、`/api/sensor-frames`、`/api/alerts/latest`、`/api/navigation/voice-route` 均保留。
- 对外跌倒 `risk_type` 仍兼容为 `fall_detected`，具体类型进入 `extra_json.fall_stage`。
