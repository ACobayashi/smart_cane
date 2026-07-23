from __future__ import annotations

import json
import sqlite3
import time
import urllib.parse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


BASE_DIR = Path(__file__).resolve().parent
WEB_DIR = BASE_DIR / "web"
DB_PATH = BASE_DIR / "smart_cane.db"
HOST = "0.0.0.0"
PORT = 8000


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
                risk_type TEXT NOT NULL,
                level TEXT NOT NULL,
                direction TEXT NOT NULL,
                sensor TEXT,
                distance_mm INTEGER,
                battery INTEGER,
                lat REAL,
                lng REAL,
                location_accuracy_m REAL,
                ai_message TEXT,
                created_at TEXT NOT NULL
            )
            """
        )


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


def ai_advice(payload: dict[str, Any]) -> str:
    """Replace this function with a real LLM API call when the API key is ready."""
    risk_type = payload.get("risk_type", "unknown")
    level = payload.get("level", "medium")
    direction = payload.get("direction", "")
    distance = payload.get("distance_mm")

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
            if parsed.path == "/api/ai-advice":
                payload = parse_json(self)
                json_response(self, 200, {"message": ai_advice(payload)})
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
        risk_type = str(payload.get("risk_type") or "").strip()
        level = str(payload.get("level") or "").strip()
        direction = str(payload.get("direction") or "").strip()
        if not device_id:
            raise ValueError("device_id is required")
        if not risk_type:
            raise ValueError("risk_type is required")
        if level not in {"low", "medium", "high"}:
            raise ValueError("level must be low, medium, or high")
        if direction not in {"front", "left", "right", "down", "unknown"}:
            raise ValueError("direction must be front, left, right, down, or unknown")

        created_at = str(payload.get("timestamp") or now_iso())
        message = ai_advice(payload)

        with db() as conn:
            loc = latest_location(conn, device_id)
            lat = payload.get("lat", loc["lat"] if loc else None)
            lng = payload.get("lng", loc["lng"] if loc else None)
            accuracy = payload.get("accuracy_m", loc["accuracy_m"] if loc else None)
            cur = conn.execute(
                """
                INSERT INTO risk_events (
                    device_id, risk_type, level, direction, sensor, distance_mm, battery,
                    lat, lng, location_accuracy_m, ai_message, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    risk_type,
                    level,
                    direction,
                    payload.get("sensor"),
                    payload.get("distance_mm"),
                    payload.get("battery"),
                    lat,
                    lng,
                    accuracy,
                    message,
                    created_at,
                ),
            )
            row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (cur.lastrowid,)).fetchone()
        json_response(self, 201, row_to_dict(row))

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
