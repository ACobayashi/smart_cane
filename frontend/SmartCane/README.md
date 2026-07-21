# SmartCane Android Frontend

This is the Android/Jetpack Compose frontend for the SmartCane backend in `../../backend`.

The app uses these backend-compatible endpoints:

- `GET /status`
- `GET /devices`
- `GET /events/latest`
- `POST /sos`
- `POST /pairing-codes`
- `GET /pairing-codes/{code}`
- `POST /care-relations/requests`
- `GET /care-relations/requests`
- `POST /care-relations/{requestId}/approve`
- `POST /care-relations/{requestId}/reject`
- `GET /care-relations`
- `DELETE /care-relations/{relationId}`
- `GET /api/map/status`
- `GET /api/map/risk-points`
- `GET /api/alerts/latest`
- `POST /api/sensor-frames`
- `POST /api/navigation/voice-route`
- `POST /api/navigation/risk-aware-route`

The FastAPI backend also keeps the ESP32 endpoints under `/api/...`, so the phone app and ESP32 firmware can use the same SQLite data.

The app polls `/api/alerts/latest` every few seconds on the blind and companion home screens. `fall_detected` and `sos` are shown to both roles; `prolonged_obstacle` and `approaching_obstacle` are shown to the companion role. `voice_request` is shown only to the blind role and switches the blind page into listening mode. The blind side also speaks the returned `voicePrompt`.

## Backend Address

The committed default backend is the shared cloud FastAPI service:

```properties
BACKEND_BASE_URL=http://118.31.221.165:8016
```

You only need to edit local Android properties when overriding the backend for a local test:

```text
D:\smartcane\frontend\SmartCane\local.properties
```

For a real Android phone on the same Wi-Fi as a local computer:

```properties
BACKEND_BASE_URL=http://192.168.1.13:8000
```

For the Android Emulator:

```properties
BACKEND_BASE_URL=http://10.0.2.2:8000
```

If `local.properties` contains `BACKEND_BASE_URL`, it overrides the committed cloud default.

## Amap Keys

Do not commit real keys.

Backend Web service key:

```text
D:\smartcane\backend\.env
AMAP_WEB_KEY=...
```

Android platform key:

```text
D:\smartcane\frontend\SmartCane\local.properties
AMAP_ANDROID_KEY=...
BACKEND_BASE_URL=http://118.31.221.165:8016
```

The Android key is injected into `AndroidManifest.xml` through a manifest placeholder. The app still calls the FastAPI backend for geocoding, route risk scoring, and LLM navigation advice, so the Web service key stays server-side.

## Run Backend

```powershell
cd D:\smartcane\backend
py -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Open this on the computer:

```text
http://127.0.0.1:8000/status
```

Open this on the phone browser, replacing the IP if needed:

```text
http://192.168.1.13:8000/status
```

If the phone cannot open it, check that phone and computer are on the same network and Windows Firewall allows Python/Uvicorn on port `8000`.

## Open Android Project

Open this folder in Android Studio:

```text
D:\smartcane\frontend\SmartCane
```

The copied project intentionally excludes local cache folders:

- `.gradle`
- `.idea`
- `.kotlin`
- `build`
- `local.properties`

Android Studio will regenerate them.

Command-line build notes:

- Use JDK 17 or JDK 21 for Gradle/Android builds.
- The system JDK 25 on this machine is too new for the stable Android Gradle Plugin.
- The wrapper is pinned to Gradle `8.10.2` and AGP `8.7.3`.

## Notes

`android:usesCleartextTraffic="true"` is enabled because the local backend uses plain HTTP for the demo.
