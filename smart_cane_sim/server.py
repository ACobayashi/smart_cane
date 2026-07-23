from __future__ import annotations

import json
import math
import os
import sqlite3
import time
import urllib.parse
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


BASE_DIR = Path(__file__).resolve().parent
WEB_DIR = BASE_DIR / "web"
DB_PATH = BASE_DIR / "smart_cane.db"
HOST = "0.0.0.0"
PORT = 8000
AI_GATEWAY_BASE_URL = os.getenv("AI_GATEWAY_BASE_URL", "https://ai-gateway.vei.volces.com/v1")
AI_GATEWAY_MODEL = os.getenv("AI_GATEWAY_MODEL", "doubao-1.5-lite-32k")
AI_GATEWAY_TIMEOUT_SECONDS = float(os.getenv("AI_GATEWAY_TIMEOUT_SECONDS", "8"))
VEI_API_KEY = os.getenv("VEI_API_KEY", "")

RISK_TYPES = {
    "front_obstacle",
    "left_obstacle",
    "right_obstacle",
    "ground_drop",
    "rough_road",
    "green_channel",
    "user_mark",
    "sos",
}
EVENT_TYPES = {
    "obstacle_detected",
    "ground_drop_detected",
    "rough_road_detected",
    "user_marked",
    "sos_triggered",
    "nearby_risk_alert",
}
LEVELS = {"low", "medium", "high"}
DIRECTIONS = {"front", "left", "right", "down", "unknown"}
ALARM_MODES = {"none", "vibration", "buzzer", "voice", "vibration_buzzer"}
REPORT_ACTIONS = {"confirm", "dismiss", "update", "pass_safe"}


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                accuracy_m REAL,
                source TEXT NOT NULL DEFAULT 'phone',
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS risk_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                user_id TEXT,
                event_type TEXT NOT NULL DEFAULT 'obstacle_detected',
                risk_type TEXT NOT NULL,
                level TEXT NOT NULL,
                direction TEXT NOT NULL,
                sensor TEXT,
                distance_mm INTEGER,
                front_mm INTEGER,
                left_mm INTEGER,
                right_mm INTEGER,
                down_mm INTEGER,
                ground_base_mm INTEGER,
                battery INTEGER,
                lat REAL,
                lng REAL,
                location_accuracy_m REAL,
                alarm_triggered INTEGER NOT NULL DEFAULT 0,
                alarm_mode TEXT NOT NULL DEFAULT 'none',
                confidence REAL NOT NULL DEFAULT 0.7,
                confirm_count INTEGER NOT NULL DEFAULT 1,
                reported_by_count INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL DEFAULT 'active',
                ai_message TEXT,
                ai_model TEXT,
                created_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS risk_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                risk_id INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                user_id TEXT,
                action TEXT NOT NULL,
                level TEXT,
                distance_mm INTEGER,
                note TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY (risk_id) REFERENCES risk_events(id)
            )
            """
        )
        migrate_db(conn)


def migrate_db(conn: sqlite3.Connection) -> None:
    risk_columns = {row["name"] for row in conn.execute("PRAGMA table_info(risk_events)")}
    migrations = {
        "user_id": "ALTER TABLE risk_events ADD COLUMN user_id TEXT",
        "event_type": "ALTER TABLE risk_events ADD COLUMN event_type TEXT NOT NULL DEFAULT 'obstacle_detected'",
        "front_mm": "ALTER TABLE risk_events ADD COLUMN front_mm INTEGER",
        "left_mm": "ALTER TABLE risk_events ADD COLUMN left_mm INTEGER",
        "right_mm": "ALTER TABLE risk_events ADD COLUMN right_mm INTEGER",
        "down_mm": "ALTER TABLE risk_events ADD COLUMN down_mm INTEGER",
        "ground_base_mm": "ALTER TABLE risk_events ADD COLUMN ground_base_mm INTEGER",
        "alarm_triggered": "ALTER TABLE risk_events ADD COLUMN alarm_triggered INTEGER NOT NULL DEFAULT 0",
        "alarm_mode": "ALTER TABLE risk_events ADD COLUMN alarm_mode TEXT NOT NULL DEFAULT 'none'",
        "confidence": "ALTER TABLE risk_events ADD COLUMN confidence REAL NOT NULL DEFAULT 0.7",
        "confirm_count": "ALTER TABLE risk_events ADD COLUMN confirm_count INTEGER NOT NULL DEFAULT 1",
        "reported_by_count": "ALTER TABLE risk_events ADD COLUMN reported_by_count INTEGER NOT NULL DEFAULT 1",
        "status": "ALTER TABLE risk_events ADD COLUMN status TEXT NOT NULL DEFAULT 'active'",
        "ai_model": "ALTER TABLE risk_events ADD COLUMN ai_model TEXT",
    }
    for column, sql in migrations.items():
        if column not in risk_columns:
            conn.execute(sql)


def latest_location(conn: sqlite3.Connection, device_id: str) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT * FROM locations
        WHERE device_id = ?
        ORDER BY id DESC
        LIMIT 1
        """,
        (device_id,),
    ).fetchone()


def local_ai_advice(payload: dict[str, Any]) -> str:
    risk_type = payload.get("risk_type", "unknown")
    level = payload.get("level", "medium")
    direction = payload.get("direction", "")
    distance = payload.get("distance_mm") or payload.get("front_mm")

    distance_text = ""
    if isinstance(distance, int):
        distance_text = f"�?{max(distance // 10, 1)} 厘米"

    if risk_type == "ground_drop":
        return "前方地面高度变化明显，可能有台阶或坑洼，请停止前进并用盲杖确认地面�?
    if risk_type == "user_mark":
        return "已记录当前位置为人工标记风险点，后续设备经过附近时会收到提醒�?
    if direction == "left":
        return f"左侧{distance_text}有障碍物，请向右侧保持距离�?
    if direction == "right":
        return f"右侧{distance_text}有障碍物，请向左侧保持距离�?
    if direction == "front":
        prefix = "高风险，" if level == "high" else ""
        return f"{prefix}前方{distance_text}有障碍物，请减速并准备绕行�?
    return "检测到环境风险，请放慢速度并注意周围变化�?


def ai_prompt(payload: dict[str, Any]) -> str:
    return (
        "你是智能盲杖的安全提示模块。请根据四路 ToF 测距和风险类型，"
        "输出一句简短、明确、适合语音播报的中文提醒。"
        "不要夸张，不要超过 40 个汉字。"
        "如果左右两侧距离差异明显，可以给出绕行方向建议。\n\n"
        f"数据 JSON：{json.dumps(payload, ensure_ascii=False)}"
    )


def call_ai_gateway(payload: dict[str, Any]) -> str | None:
    if not VEI_API_KEY:
        return None

    request_body = {
        "model": AI_GATEWAY_MODEL,
        "messages": [
            {
                "role": "system",
                "content": "你负责把智能盲杖传感器数据转换成安全、简短的中文提醒。",
            },
            {"role": "user", "content": ai_prompt(payload)},
        ],
        "temperature": 0.2,
    }
    data = json.dumps(request_body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{AI_GATEWAY_BASE_URL.rstrip('/')}/chat/completions",
        data=data,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {VEI_API_KEY}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=AI_GATEWAY_TIMEOUT_SECONDS) as resp:
            result = json.loads(resp.read().decode("utf-8"))
    except (TimeoutError, urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        print(f"AI gateway failed, fallback to local advice: {exc}")
        return None

    try:
        content = result["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        return None
    return str(content).strip() or None


def ai_advice(payload: dict[str, Any]) -> tuple[str, str]:
    remote = call_ai_gateway(payload)
    if remote:
        return remote, AI_GATEWAY_MODEL
    return local_ai_advice(payload), "local_fallback"


def parse_json(handler: BaseHTTPRequestHandler) -> dict[str, Any]:
    length = int(handler.headers.get("Content-Length", "0"))
    raw = handler.rfile.read(length).decode("utf-8") if length else "{}"
    if not raw:
        return {}
    return json.loads(raw)


def json_response(handler: BaseHTTPRequestHandler, status: int, payload: Any) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    handler.send_header("Access-Control-Allow-Headers", "Content-Type")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def bool_int(value: Any) -> int:
    return 1 if value in {True, 1, "1", "true", "True", "yes"} else 0


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    radius_m = 6371000
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lng2 - lng1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius_m * math.atan2(math.sqrt(a), math.sqrt(1 - a))


class SmartCaneHandler(BaseHTTPRequestHandler):
    server_version = "SmartCaneSim/0.1"

    def do_OPTIONS(self) -> None:
        json_response(self, 200, {"ok": True})

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/health":
            json_response(self, 200, {"ok": True, "time": now_iso()})
            return
        if parsed.path == "/api/risk-events":
            self.get_risk_events(parsed)
            return
        if parsed.path == "/api/nearby-risks":
            self.get_nearby_risks(parsed)
            return
        if parsed.path == "/api/locations/latest":
            self.get_latest_location(parsed)
            return
        self.serve_static(parsed.path)

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        try:
            if parsed.path == "/api/locations":
                self.create_location()
                return
            if parsed.path == "/api/risk-events":
                self.create_risk_event()
                return
            if parsed.path == "/api/risk-reports":
                self.create_risk_report()
                return
            if parsed.path == "/api/ai-advice":
                payload = parse_json(self)
                message, model = ai_advice(payload)
                json_response(self, 200, {"message": message, "model": model})
                return
            json_response(self, 404, {"error": "not_found"})
        except json.JSONDecodeError:
            json_response(self, 400, {"error": "invalid_json"})
        except ValueError as exc:
            json_response(self, 400, {"error": str(exc)})

    def create_location(self) -> None:
        payload = parse_json(self)
        device_id = str(payload.get("device_id") or "").strip()
        if not device_id:
            raise ValueError("device_id is required")
        lat = float(payload["lat"])
        lng = float(payload["lng"])
        accuracy_m = payload.get("accuracy_m")
        source = str(payload.get("source") or "phone")
        created_at = str(payload.get("timestamp") or now_iso())

        with db() as conn:
            cur = conn.execute(
                """
                INSERT INTO locations (device_id, lat, lng, accuracy_m, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (device_id, lat, lng, accuracy_m, source, created_at),
            )
            row = conn.execute("SELECT * FROM locations WHERE id = ?", (cur.lastrowid,)).fetchone()
        json_response(self, 201, row_to_dict(row))

    def create_risk_event(self) -> None:
        payload = parse_json(self)
        device_id = str(payload.get("device_id") or "").strip()
        user_id = str(payload.get("user_id") or "").strip() or None
        event_type = str(payload.get("event_type") or "obstacle_detected").strip()
        risk_type = str(payload.get("risk_type") or "").strip()
        level = str(payload.get("level") or "").strip()
        direction = str(payload.get("direction") or "").strip()
        if not device_id:
            raise ValueError("device_id is required")
        if event_type not in EVENT_TYPES:
            raise ValueError(f"event_type must be one of {sorted(EVENT_TYPES)}")
        if not risk_type:
            raise ValueError("risk_type is required")
        if risk_type not in RISK_TYPES:
            raise ValueError(f"risk_type must be one of {sorted(RISK_TYPES)}")
        if level not in LEVELS:
            raise ValueError("level must be low, medium, or high")
        if direction not in DIRECTIONS:
            raise ValueError("direction must be front, left, right, down, or unknown")
        alarm_mode = str(payload.get("alarm_mode") or "none").strip()
        if alarm_mode not in ALARM_MODES:
            raise ValueError(f"alarm_mode must be one of {sorted(ALARM_MODES)}")

        created_at = str(payload.get("timestamp") or now_iso())
        message, ai_model = ai_advice(payload)

        with db() as conn:
            loc = latest_location(conn, device_id)
            lat = payload.get("lat", loc["lat"] if loc else None)
            lng = payload.get("lng", loc["lng"] if loc else None)
            accuracy = payload.get("accuracy_m", loc["accuracy_m"] if loc else None)
            cur = conn.execute(
                """
                INSERT INTO risk_events (
                    device_id, user_id, event_type, risk_type, level, direction, sensor,
                    distance_mm, front_mm, left_mm, right_mm, down_mm, ground_base_mm,
                    battery, lat, lng, location_accuracy_m, alarm_triggered, alarm_mode,
                    confidence, confirm_count, reported_by_count, status, ai_message,
                    ai_model, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    user_id,
                    event_type,
                    risk_type,
                    level,
                    direction,
                    payload.get("sensor"),
                    payload.get("distance_mm"),
                    payload.get("front_mm"),
                    payload.get("left_mm"),
                    payload.get("right_mm"),
                    payload.get("down_mm"),
                    payload.get("ground_base_mm"),
                    payload.get("battery"),
                    lat,
                    lng,
                    accuracy,
                    bool_int(payload.get("alarm_triggered")),
                    alarm_mode,
                    payload.get("confidence", 0.7),
                    1,
                    1,
                    "active",
                    message,
                    ai_model,
                    created_at,
                ),
            )
            row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (cur.lastrowid,)).fetchone()
        json_response(self, 201, row_to_dict(row))

    def create_risk_report(self) -> None:
        payload = parse_json(self)
        risk_id = int(payload.get("risk_id"))
        device_id = str(payload.get("device_id") or "").strip()
        action = str(payload.get("action") or "").strip()
        if not device_id:
            raise ValueError("device_id is required")
        if action not in REPORT_ACTIONS:
            raise ValueError(f"action must be one of {sorted(REPORT_ACTIONS)}")
        level = str(payload.get("level") or "").strip() or None
        if level and level not in LEVELS:
            raise ValueError("level must be low, medium, or high")

        created_at = str(payload.get("timestamp") or now_iso())
        with db() as conn:
            event = conn.execute("SELECT * FROM risk_events WHERE id = ?", (risk_id,)).fetchone()
            if event is None:
                raise ValueError("risk_id not found")
            cur = conn.execute(
                """
                INSERT INTO risk_reports (risk_id, device_id, user_id, action, level, distance_mm, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    risk_id,
                    device_id,
                    payload.get("user_id"),
                    action,
                    level,
                    payload.get("distance_mm"),
                    payload.get("note"),
                    created_at,
                ),
            )
            if action == "confirm":
                conn.execute(
                    """
                    UPDATE risk_events
                    SET confirm_count = confirm_count + 1,
                        reported_by_count = reported_by_count + 1,
                        confidence = MIN(confidence + 0.08, 0.99),
                        status = 'active'
                    WHERE id = ?
                    """,
                    (risk_id,),
                )
            elif action == "dismiss":
                conn.execute(
                    """
                    UPDATE risk_events
                    SET confidence = MAX(confidence - 0.2, 0.0),
                        status = CASE WHEN confidence <= 0.25 THEN 'inactive' ELSE status END
                    WHERE id = ?
                    """,
                    (risk_id,),
                )
            elif action == "update" and level:
                conn.execute("UPDATE risk_events SET level = ? WHERE id = ?", (level, risk_id))
            report = conn.execute("SELECT * FROM risk_reports WHERE id = ?", (cur.lastrowid,)).fetchone()
        json_response(self, 201, row_to_dict(report))

    def get_risk_events(self, parsed: urllib.parse.ParseResult) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        device_id = query.get("device_id", [None])[0]
        limit = int(query.get("limit", ["100"])[0])
        limit = min(max(limit, 1), 500)

        sql = "SELECT * FROM risk_events"
        params: list[Any] = []
        if device_id:
            sql += " WHERE device_id = ?"
            params.append(device_id)
        sql += " ORDER BY id DESC LIMIT ?"
        params.append(limit)

        with db() as conn:
            rows = conn.execute(sql, params).fetchall()
        json_response(self, 200, [row_to_dict(row) for row in rows])

    def get_nearby_risks(self, parsed: urllib.parse.ParseResult) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        lat = float(query["lat"][0])
        lng = float(query["lng"][0])
        radius_m = float(query.get("radius_m", ["100"])[0])
        radius_m = min(max(radius_m, 1), 2000)
        limit = int(query.get("limit", ["50"])[0])
        limit = min(max(limit, 1), 200)

        with db() as conn:
            rows = conn.execute(
                """
                SELECT * FROM risk_events
                WHERE lat IS NOT NULL AND lng IS NOT NULL AND status = 'active'
                ORDER BY id DESC
                LIMIT 500
                """
            ).fetchall()
        risks = []
        for row in rows:
            item = row_to_dict(row)
            distance = haversine_m(lat, lng, float(item["lat"]), float(item["lng"]))
            if distance <= radius_m:
                item["risk_id"] = item["id"]
                item["distance_m"] = round(distance, 1)
                item["last_seen_at"] = item["created_at"]
                risks.append(item)
        risks.sort(key=lambda item: item["distance_m"])
        json_response(self, 200, {"lat": lat, "lng": lng, "radius_m": radius_m, "risks": risks[:limit]})

    def get_latest_location(self, parsed: urllib.parse.ParseResult) -> None:
        query = urllib.parse.parse_qs(parsed.query)
        device_id = query.get("device_id", ["cane_001"])[0]
        with db() as conn:
            row = latest_location(conn, device_id)
        json_response(self, 200, row_to_dict(row) if row else None)

    def serve_static(self, path: str) -> None:
        if path in {"/", ""}:
            file_path = WEB_DIR / "index.html"
        else:
            file_path = (WEB_DIR / path.lstrip("/")).resolve()
            if WEB_DIR.resolve() not in file_path.parents:
                json_response(self, 403, {"error": "forbidden"})
                return

        if not file_path.exists() or not file_path.is_file():
            json_response(self, 404, {"error": "not_found"})
            return

        content_type = "text/plain; charset=utf-8"
        if file_path.suffix == ".html":
            content_type = "text/html; charset=utf-8"
        elif file_path.suffix == ".css":
            content_type = "text/css; charset=utf-8"
        elif file_path.suffix == ".js":
            content_type = "application/javascript; charset=utf-8"

        body = file_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[{time.strftime('%H:%M:%S')}] {self.address_string()} {fmt % args}")


def main() -> None:
    init_db()
    server = ThreadingHTTPServer((HOST, PORT), SmartCaneHandler)
    print(f"Smart cane simulator server: http://127.0.0.1:{PORT}")
    print("Open the same URL on your phone to upload GPS location.")
    server.serve_forever()


if __name__ == "__main__":
    main()
