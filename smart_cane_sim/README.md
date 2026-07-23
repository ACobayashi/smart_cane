# Smart Cane Legacy Simulator

This directory is the old software-only demo path. The current product path is:

```text
ESP32-C5 Arduino firmware -> FastAPI backend -> Android app
```

Use the group cloud backend:

```text
http://118.31.221.165:8016
```

If this legacy simulator is still needed:

```bash
cd smart_cane_sim
python server.py
python esp32_simulator.py
```

The real hardware firmware and Android app should use the same backend base URL:

```text
http://118.31.221.165:8016
```
