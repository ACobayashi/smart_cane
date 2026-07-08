from __future__ import annotations

import json
import math
import os
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import httpx
from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "smartcane.db"
LEVEL_RANK = {"low": 0, "medium": 1, "high": 2}


try:
    from dotenv import load_dotenv

    load_dotenv(BASE_DIR / ".env")
    load_dotenv(BASE_DIR.parent / ".env", override=False)
except ImportError:
    pass


class EventCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    risk_type: str = Field(..., min_length=1)
    risk_level: str = Field(..., pattern="^(low|medium|high)$")
    front_cm: Optional[int] = None
    left_cm: Optional[int] = None
    right_cm: Optional[int] = None
    down_cm: Optional[int] = None
    extra_json: Optional[Any] = None
    timestamp: Optional[str] = None


class LocationCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    source: str = "gps"
    accuracy_m: Optional[float] = None
    satellite_count: Optional[int] = None
    timestamp: Optional[str] = None


class AdviceRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    risk_type: str = "none"
    risk_level: str = Field("low", pattern="^(low|medium|high)$")
    front_cm: Optional[int] = None
    left_cm: Optional[int] = None
    right_cm: Optional[int] = None
    down_cm: Optional[int] = None
    extra: Optional[str] = None
    nearby_radius_m: float = Field(80.0, gt=0, le=5000)


class TextCommandRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    text: str = Field(..., min_length=1)
    lat: Optional[float] = None
    lng: Optional[float] = None


app = FastAPI(title="Smart Cane Collaborative Risk Backend", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS risk_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                risk_type TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                front_cm INTEGER,
                left_cm INTEGER,
                right_cm INTEGER,
                down_cm INTEGER,
                extra_json TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                source TEXT NOT NULL,
                accuracy_m REAL,
                satellite_count INTEGER
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_events_lat_lng ON risk_events(lat, lng)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_events_level ON risk_events(risk_level)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_device_locations_device ON device_locations(device_id)")


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def normalize_extra(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    radius_m = 6371000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lam = math.radians(lng2 - lng1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lam / 2) ** 2
    return 2 * radius_m * math.asin(math.sqrt(a))


def nearby_summary(lat: float, lng: float, radius: float) -> dict[str, Any]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM risk_events WHERE lat IS NOT NULL AND lng IS NOT NULL").fetchall()

    nearby: list[dict[str, Any]] = []
    for row in rows:
        item = row_to_dict(row)
        distance_m = haversine_m(lat, lng, float(item["lat"]), float(item["lng"]))
        if distance_m <= radius:
            item["distance_m"] = round(distance_m, 2)
            nearby.append(item)

    nearby.sort(key=lambda item: item["id"], reverse=True)
    high_count = sum(1 for item in nearby if item["risk_level"] == "high")
    medium_count = sum(1 for item in nearby if item["risk_level"] == "medium")
    max_level = "low"
    for item in nearby:
        if LEVEL_RANK[item["risk_level"]] > LEVEL_RANK[max_level]:
            max_level = item["risk_level"]

    return {
        "risk_count": len(nearby),
        "high_count": high_count,
        "medium_count": medium_count,
        "max_level": max_level,
        "recent_events": nearby[:10],
    }


def chat_config() -> dict[str, str]:
    provider = env("LLM_PROVIDER", "ark").lower()
    if provider == "openai":
        return {
            "provider": "openai",
            "api_key": env("OPENAI_API_KEY"),
            "base_url": env("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
            "model": env("OPENAI_MODEL", "gpt-4.1-mini"),
        }
    return {
        "provider": "ark",
        "api_key": env("ARK_API_KEY"),
        "base_url": env("ARK_OPENAI_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3").rstrip("/"),
        "model": env("ARK_MODEL", "doubao-seed-2-1-pro-260628"),
    }


def stt_config() -> dict[str, str]:
    provider = env("STT_PROVIDER", "openai").lower()
    if provider == "ark":
        return {
            "provider": "ark",
            "api_key": env("ARK_API_KEY"),
            "base_url": env("ARK_OPENAI_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3").rstrip("/"),
            "model": env("ARK_STT_MODEL", env("STT_MODEL", "")),
        }
    return {
        "provider": "openai",
        "api_key": env("OPENAI_API_KEY"),
        "base_url": env("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
        "model": env("OPENAI_STT_MODEL", env("STT_MODEL", "whisper-1")),
    }


def ai_enabled() -> bool:
    cfg = chat_config()
    return bool(cfg["api_key"] and cfg["model"] and cfg["base_url"])


def fallback_advice(req: AdviceRequest, history: dict[str, Any]) -> str:
    if req.risk_type == "sos":
        return "SOS already sent. Stay where you are if safe."
    if req.risk_type == "ground_drop" or (req.down_cm is not None and req.down_cm > 75):
        return "Stop. Check the ground ahead before moving."
    if req.risk_level == "high":
        if req.left_cm is not None and req.right_cm is not None:
            if req.left_cm > req.right_cm and req.left_cm > 90:
                return "High risk ahead. Turn left slowly."
            if req.right_cm > req.left_cm and req.right_cm > 90:
                return "High risk ahead. Turn right slowly."
        return "High risk ahead. Stop and probe carefully."
    if req.risk_level == "medium":
        return "Slow down. Keep scanning left and right."
    if history["high_count"] >= 2:
        return "Nearby history has high risks. Slow down."
    return "Path looks clear. Continue carefully."


async def call_chat_completion(messages: list[dict[str, str]], temperature: float = 0.2) -> tuple[Optional[str], dict[str, Any]]:
    cfg = chat_config()
    meta = {"provider": cfg["provider"], "model": cfg["model"], "enabled": bool(cfg["api_key"])}
    if not cfg["api_key"]:
        return None, meta

    payload = {
        "model": cfg["model"],
        "messages": messages,
        "temperature": temperature,
    }
    headers = {
        "Authorization": f"Bearer {cfg['api_key']}",
        "Content-Type": "application/json",
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(f"{cfg['base_url']}/chat/completions", headers=headers, json=payload)
    response.raise_for_status()
    data = response.json()
    content = data["choices"][0]["message"]["content"]
    return str(content).strip(), meta


async def generate_advice(req: AdviceRequest, history: dict[str, Any]) -> dict[str, Any]:
    fallback = fallback_advice(req, history)
    messages = [
        {
            "role": "system",
            "content": (
                "You are a safety assistant for a smart cane. "
                "Return one short practical instruction in Chinese, no markdown, no diagnosis, under 40 Chinese characters. "
                "Prefer stop/slow/left/right guidance based on sensor data."
            ),
        },
        {
            "role": "user",
            "content": json.dumps(
                {
                    "device_id": req.device_id,
                    "risk_type": req.risk_type,
                    "risk_level": req.risk_level,
                    "front_cm": req.front_cm,
                    "left_cm": req.left_cm,
                    "right_cm": req.right_cm,
                    "down_cm": req.down_cm,
                    "nearby_history": {
                        "risk_count": history["risk_count"],
                        "high_count": history["high_count"],
                        "medium_count": history["medium_count"],
                        "max_level": history["max_level"],
                    },
                    "extra": req.extra,
                },
                ensure_ascii=False,
            ),
        },
    ]

    try:
        content, meta = await call_chat_completion(messages)
    except Exception as exc:
        return {
            "advice": fallback,
            "fallback": True,
            "error": str(exc),
            "provider": chat_config()["provider"],
            "model": chat_config()["model"],
        }

    if not content:
        meta = meta if "meta" in locals() else {"provider": chat_config()["provider"], "model": chat_config()["model"]}
        return {**meta, "advice": fallback, "fallback": True}
    return {**meta, "advice": content, "fallback": False}


def fallback_command(text: str) -> dict[str, Any]:
    normalized = text.lower()
    if any(word in normalized for word in ["sos", "help", "救命", "求助"]):
        return {"intent": "sos", "action": "trigger_sos", "confidence": 0.95, "reply": "已识别求助指令"}
    if any(word in normalized for word in ["upload", "mark", "标记", "记录"]):
        return {"intent": "mark_risk", "action": "upload_user_mark", "confidence": 0.8, "reply": "已识别风险标记指令"}
    if any(word in normalized for word in ["nearby", "附近", "风险"]):
        return {"intent": "query_risk", "action": "query_nearby_risks", "confidence": 0.75, "reply": "已识别风险查询指令"}
    if any(word in normalized for word in ["repeat", "重复", "再说"]):
        return {"intent": "repeat", "action": "repeat_last_prompt", "confidence": 0.7, "reply": "已识别重复提示指令"}
    return {"intent": "unknown", "action": "none", "confidence": 0.35, "reply": "未识别明确指令"}


async def parse_command_with_llm(text: str, device_id: str) -> dict[str, Any]:
    fallback = fallback_command(text)
    messages = [
        {
            "role": "system",
            "content": (
                "Classify a smart cane voice command. "
                "Return compact JSON only with keys: intent, action, confidence, reply. "
                "Allowed actions: trigger_sos, upload_user_mark, query_nearby_risks, repeat_last_prompt, switch_mode, none."
            ),
        },
        {"role": "user", "content": json.dumps({"device_id": device_id, "text": text}, ensure_ascii=False)},
    ]
    try:
        content, meta = await call_chat_completion(messages, temperature=0.0)
        if not content:
            return {**fallback, "fallback": True, "provider": meta["provider"], "model": meta["model"]}
        parsed = json.loads(content)
        return {**parsed, "fallback": False, "provider": meta["provider"], "model": meta["model"]}
    except Exception as exc:
        return {**fallback, "fallback": True, "error": str(exc), "provider": chat_config()["provider"], "model": chat_config()["model"]}


@app.on_event("startup")
def on_startup() -> None:
    init_db()


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "time": now_iso(), "database": str(DB_PATH)}


@app.get("/api/ai/status")
def ai_status() -> dict[str, Any]:
    chat = chat_config()
    stt = stt_config()
    return {
        "llm_provider": chat["provider"],
        "llm_model": chat["model"],
        "llm_configured": bool(chat["api_key"]),
        "stt_provider": stt["provider"],
        "stt_model": stt["model"],
        "stt_configured": bool(stt["api_key"] and stt["model"]),
    }


@app.post("/api/events", status_code=201)
def create_event(event: EventCreate) -> dict[str, Any]:
    timestamp = event.timestamp or now_iso()
    risk_level = event.risk_level.lower()
    if risk_level not in LEVEL_RANK:
        raise HTTPException(status_code=400, detail="risk_level must be low, medium, or high")

    with db() as conn:
        cur = conn.execute(
            """
            INSERT INTO risk_events (
                device_id, timestamp, lat, lng, risk_type, risk_level,
                front_cm, left_cm, right_cm, down_cm, extra_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event.device_id,
                timestamp,
                event.lat,
                event.lng,
                event.risk_type,
                risk_level,
                event.front_cm,
                event.left_cm,
                event.right_cm,
                event.down_cm,
                normalize_extra(event.extra_json),
            ),
        )
        row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (cur.lastrowid,)).fetchone()

    return row_to_dict(row)


@app.get("/api/events")
def list_events(limit: int = Query(200, ge=1, le=1000)) -> list[dict[str, Any]]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM risk_events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return [row_to_dict(row) for row in rows]


@app.post("/api/locations", status_code=201)
def create_location(location: LocationCreate) -> dict[str, Any]:
    timestamp = location.timestamp or now_iso()
    with db() as conn:
        cur = conn.execute(
            """
            INSERT INTO device_locations (
                device_id, timestamp, lat, lng, source, accuracy_m, satellite_count
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                location.device_id,
                timestamp,
                location.lat,
                location.lng,
                location.source,
                location.accuracy_m,
                location.satellite_count,
            ),
        )
        row = conn.execute("SELECT * FROM device_locations WHERE id = ?", (cur.lastrowid,)).fetchone()
    return row_to_dict(row)


@app.get("/api/locations/latest")
def latest_location(device_id: str = Query(..., min_length=1)) -> Optional[dict[str, Any]]:
    with db() as conn:
        row = conn.execute(
            "SELECT * FROM device_locations WHERE device_id = ? ORDER BY id DESC LIMIT 1",
            (device_id,),
        ).fetchone()
    return row_to_dict(row) if row else None


@app.get("/api/risks/nearby")
def nearby_risks(
    lat: float = Query(...),
    lng: float = Query(...),
    radius: float = Query(80.0, gt=0, le=5000),
) -> dict[str, Any]:
    return nearby_summary(lat, lng, radius)


@app.post("/api/ai/advice")
async def ai_advice(req: AdviceRequest) -> dict[str, Any]:
    history = nearby_summary(req.lat, req.lng, req.nearby_radius_m)
    result = await generate_advice(req, history)
    return {**result, "nearby": history}


@app.post("/api/voice/text-command")
async def text_command(req: TextCommandRequest) -> dict[str, Any]:
    result = await parse_command_with_llm(req.text, req.device_id)
    return {"text": req.text, **result}


@app.post("/api/voice/transcribe")
async def transcribe_voice(
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
    prompt: Optional[str] = Form(None),
) -> dict[str, Any]:
    cfg = stt_config()
    if not cfg["api_key"] or not cfg["model"]:
        raise HTTPException(status_code=503, detail="speech recognition is not configured")

    content = await file.read()
    data: dict[str, str] = {"model": cfg["model"]}
    if language:
        data["language"] = language
    if prompt:
        data["prompt"] = prompt

    files = {
        "file": (
            file.filename or "audio.wav",
            content,
            file.content_type or "application/octet-stream",
        )
    }
    headers = {"Authorization": f"Bearer {cfg['api_key']}"}

    async with httpx.AsyncClient(timeout=90.0) as client:
        response = await client.post(f"{cfg['base_url']}/audio/transcriptions", headers=headers, data=data, files=files)
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)
    payload = response.json()
    return {
        "provider": cfg["provider"],
        "model": cfg["model"],
        "text": payload.get("text", ""),
        "raw": payload,
    }


@app.post("/api/voice/command")
async def voice_command(
    device_id: str = Form(...),
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
) -> dict[str, Any]:
    transcript = await transcribe_voice(file=file, language=language, prompt=None)
    parsed = await parse_command_with_llm(transcript["text"], device_id)
    return {"device_id": device_id, "transcript": transcript["text"], **parsed}


if __name__ == "__main__":
    import uvicorn

    init_db()
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
