# SmartCane_IDF_Bringup

这是 ESP-IDF 版硬件 bring-up 工程，用来验证：

- VS Code ESP-IDF 插件配置正确。
- ESP-IDF 能识别 `esp32c5` target。
- ESP32-C5 能编译、烧录、串口输出。

它和 `smart_cane_sim` 不一样：

- `smart_cane_sim` 是电脑端后端和网页，不是 ESP-IDF 工程。
- 这个目录才是 ESP-IDF 工程，根目录下有 `CMakeLists.txt`。

## VS Code 使用方式

在 VS Code 中打开这个目录：

```text
/Users/akobayashi/Documents/IoT/esp_idf/SmartCane_IDF_Bringup
```

然后执行：

```text
ESP-IDF: Set Espressif Device Target
```

选择：

```text
esp32c5
```

再执行 build / flash / monitor。

## 命令行方式

如果 ESP-IDF 环境已激活：

```bash
idf.py set-target esp32c5
idf.py build
idf.py flash monitor
```

成功后串口会循环输出：

```text
ESP32-C5 smart cane ESP-IDF bring-up
Board is alive, counter=...
```
