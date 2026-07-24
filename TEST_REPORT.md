# 智能盲杖全链路测试报告

测试日期：2026-07-24

## 实际执行结果

- `python -m compileall -q backend tests`：通过。
- `pytest -q`：`14 passed, 2 warnings`。
- `.\gradlew.bat :app:assembleDebug`：`BUILD SUCCESSFUL`。
- APK：`frontend/SmartCane/app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check`：无补丁格式错误。

两条 Python warning 均为 FastAPI `on_event` 弃用提示，不影响本次功能。

## 自动测试覆盖

- 基准55 cm时：74 cm不报警、75 cm连续两帧触发落差报警。
- 下视距离151 cm连续两帧报警。
- 400/no-target哨兵值不报警。
- 35 cm等其他下视距离不报警。
- 慢速跌倒 pending 对客户端可见，但不形成正式告警。
- 相同 `fall_event_id` 跨运行时状态只保存一条记录。
- 正式跌倒后30秒内普通传感器告警被抑制。
- 导航偏航、到达连续三帧确认。
- 导航返回距下一动作距离。
- 道路 traversal 生命周期与取消状态。
- Android 跌倒取消命令去重且仅投递一次。
- 固件源代码静态契约：20 cm、150 cm、400、pending及30秒抑制字段存在。

## 构建边界

当前机器没有 `arduino-cli`、PlatformIO 或 ESP-IDF，因此没有声称 ESP32-C5 固件已完成目标板编译或实物传感器验证。固件逻辑、接口字段和自动化契约测试已完成；最终仍需在实际硬件上校准安装角度、测距噪声和BMI270阈值。
