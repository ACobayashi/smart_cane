from __future__ import annotations

import asyncio
import hashlib
import json
import math
import os
import re
import secrets
import sqlite3
import tempfile
import uuid
import asyncio
import base64
import io
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

import httpx
from deep_model import score_deep_risk
from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.getenv("SMARTCANE_DB_PATH", str(BASE_DIR / "smartcane.db")))
LEVEL_RANK = {"low": 0, "medium": 1, "high": 2}
DEVICE_OFFLINE_SECONDS = 60
AMAP_BASE_URL = "https://restapi.amap.com/v3"

FRONT_WARN_CM = 80
FRONT_DANGER_CM = 40
SIDE_SAFE_CM = 55
SIDE_NEAR_CM = 55
SIDE_DANGER_CM = 30
SIDE_BLOCKED_CM = 30
GROUND_BASE_CM = 55
GROUND_DROP_THRESHOLD_CM = 20
GROUND_DROP_DELTA_CM = 20
DOWN_OBSTACLE_CM = 20
DOWN_DROP_CM = 20
DOWN_NO_TARGET_CM = 390
DOWN_STEP_EDGE_MIN_CM = 20
DOWN_STEP_EDGE_MAX_CM = 389
DEFAULT_NEARBY_RADIUS_M = 80.0
ROUTE_RISK_BUFFER_M = 8.0
WALKING_NAVIGATION_MAX_DISTANCE_M = 3000.0
RISK_POINT_CLUSTER_RADIUS_M = 12.0
LEGACY_SIM_POINT_LAT = 31.2304
LEGACY_SIM_POINT_LNG = 121.4737
LEGACY_SIM_POINT_RADIUS_M = 80.0
RISK_POINT_TRANSIENT_TTL_SECONDS = 2 * 60 * 60
RISK_POINT_FIXED_TTL_SECONDS = 7 * 24 * 60 * 60
RISK_POINT_EMERGENCY_TTL_SECONDS = 30 * 60

HARDWARE_PROFILE: dict[str, Any] = {
    "controller": "ESP32-C5 Dev Module / SensairShuttle compatible",
    "i2c": {
        "sda_gpio": 2,
        "scl_gpio": 3,
        "tca9548a_addr": "0x70",
        "mpr121_addr": "0x5A",
        "pca9685_addr": "0x40",
    },
    "tof_sensors": {
        "front": {"tca_channel": 2, "sensor": "VL53L1X"},
        "left": {"tca_channel": 3, "sensor": "VL53L1X"},
        "right": {"tca_channel": 4, "sensor": "VL53L1X"},
        "down": {"tca_channel": 5, "sensor": "VL53L1X"},
    },
    "touch": {
        "module": "MPR121 / HW-017",
        "tca_channel": 7,
        "electrodes": {
            "0": "query_status",
            "1": "long_press_upload_user_mark",
            "2": "repeat_prompt",
            "3": "toggle_local_network_mode",
            "4": "previous_or_left",
            "5": "next_or_right",
        },
    },
    "actuators": {
        "buzzer": {"gpio": 4, "active_level": "LOW", "idle_level": "HIGH"},
        "sos_button": {"gpio": 5, "active_level": "LOW", "hold_ms": 2000},
        "vibration_motors": {
            "mode": "pca9685_pwm",
            "address": "0x40",
            "note": "Use PCA9685 channels, not ESP32 GPIO8/9/10. GPIO8/9/10 are reserved by the BMI270/BMM350 shuttle board.",
            "left": {"pca_channel": 8},
            "right": {"pca_channel": 9},
            "center": {"pca_channel": 10},
        },
    },
    "built_in_sensors": {
        "imu": {"sensor": "BMI270", "use": "fall_detected"},
        "magnetometer": {"sensor": "BMM350", "use": "heading_reference_not_position"},
        "location": {"source": "phone_amap_or_mock", "note": "ESP32-C5 board has no GPS receiver"},
    },
    "thresholds_cm": {
        "front_warn": FRONT_WARN_CM,
        "front_danger": FRONT_DANGER_CM,
        "side_safe": SIDE_SAFE_CM,
        "side_near": SIDE_NEAR_CM,
        "side_danger": SIDE_DANGER_CM,
        "ground_base": GROUND_BASE_CM,
        "ground_drop_threshold": GROUND_DROP_THRESHOLD_CM,
        "down_obstacle": DOWN_OBSTACLE_CM,
        "down_step_edge_min": DOWN_STEP_EDGE_MIN_CM,
        "down_step_edge_max": DOWN_STEP_EDGE_MAX_CM,
    },
}


try:
    from dotenv import load_dotenv

    load_dotenv(BASE_DIR / ".env")
    load_dotenv(BASE_DIR.parent / ".env", override=False)
except ImportError:
    pass


class EventCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    device_name: Optional[str] = None
    lat: float
    lng: float
    risk_type: str = Field(..., min_length=1)
    risk_level: Optional[str] = Field(None, pattern="^(low|medium|high)$")
    level: Optional[str] = Field(None, pattern="^(low|medium|high)$")
    direction: Optional[str] = None
    sensor: Optional[str] = None
    distance_mm: Optional[int] = None
    battery: Optional[float] = None
    battery_percent: Optional[float] = None
    front_cm: Optional[int] = None
    left_cm: Optional[int] = None
    right_cm: Optional[int] = None
    down_cm: Optional[int] = None
    risk_score: Optional[float] = None
    confidence: Optional[float] = Field(None, ge=0, le=1)
    fall_event_id: Optional[str] = None
    voice_prompt: Optional[str] = None
    feedback_json: Optional[Any] = None
    extra_json: Optional[Any] = None
    timestamp: Optional[str] = None


class LocationCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    source: str = "gps"
    provider: Optional[str] = None
    quality: Optional[str] = None
    accuracy_m: Optional[float] = None
    hdop: Optional[float] = None
    fix_quality: Optional[int] = None
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
    accuracy_m: Optional[float] = None
    location_quality: Optional[str] = None
    extra: Optional[str] = None
    nearby_radius_m: float = Field(80.0, gt=0, le=5000)


class AiAdviceCompatRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    risk_type: str = "none"
    risk_level: Optional[str] = Field(None, pattern="^(low|medium|high)$")
    level: Optional[str] = Field(None, pattern="^(low|medium|high)$")
    front_cm: Optional[int] = None
    left_cm: Optional[int] = None
    right_cm: Optional[int] = None
    down_cm: Optional[int] = None
    front_mm: Optional[int] = None
    left_mm: Optional[int] = None
    right_mm: Optional[int] = None
    down_mm: Optional[int] = None
    accuracy_m: Optional[float] = None
    location_quality: Optional[str] = None
    extra: Optional[str] = None
    nearby_radius_m: float = Field(80.0, gt=0, le=5000)

    def to_advice_request(self) -> AdviceRequest:
        return AdviceRequest(
            device_id=self.device_id,
            lat=self.lat,
            lng=self.lng,
            risk_type=self.risk_type,
            risk_level=self.risk_level or self.level or "low",
            front_cm=self.front_cm if self.front_cm is not None else mm_to_cm(self.front_mm),
            left_cm=self.left_cm if self.left_cm is not None else mm_to_cm(self.left_mm),
            right_cm=self.right_cm if self.right_cm is not None else mm_to_cm(self.right_mm),
            down_cm=self.down_cm if self.down_cm is not None else mm_to_cm(self.down_mm),
            accuracy_m=self.accuracy_m,
            location_quality=self.location_quality,
            extra=self.extra,
            nearby_radius_m=self.nearby_radius_m,
        )


class DeepRiskRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    lat: float
    lng: float
    risk_type: str = "none"
    risk_level: str = Field("low", pattern="^(low|medium|high)$")
    front_cm: Optional[int] = None
    left_cm: Optional[int] = None
    right_cm: Optional[int] = None
    down_cm: Optional[int] = None
    accuracy_m: Optional[float] = None
    location_quality: Optional[str] = None
    nearby_radius_m: float = Field(80.0, gt=0, le=5000)


class TextCommandRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    text: str = Field(..., min_length=1)
    lat: Optional[float] = None
    lng: Optional[float] = None


class SensorFrameCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    device_name: Optional[str] = None
    firmware_build: Optional[str] = None
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)
    front_cm: Optional[int] = Field(None, ge=0, le=450)
    left_cm: Optional[int] = Field(None, ge=0, le=450)
    right_cm: Optional[int] = Field(None, ge=0, le=450)
    down_cm: Optional[int] = Field(None, ge=0, le=450)
    down_raw_cm: Optional[int] = Field(None, ge=0, le=450)
    down_valid: Optional[bool] = None
    down_status: Optional[str] = None
    risk_type: Optional[str] = None
    risk_level: Optional[str] = Field(None, pattern="^(low|medium|high)$")
    direction: Optional[str] = None
    reason: Optional[str] = None
    confidence: Optional[float] = Field(None, ge=0, le=1)
    battery: Optional[float] = Field(None, ge=-1, le=100)
    heading_deg: Optional[float] = Field(None, ge=0, lt=360)
    accel_x_g: Optional[float] = None
    accel_y_g: Optional[float] = None
    accel_z_g: Optional[float] = None
    accel_total_g: Optional[float] = None
    fall_event_id: Optional[str] = None
    fall_pending: Optional[bool] = None
    fall_detected: Optional[bool] = None
    fall_stage: Optional[str] = None
    fall_confidence: Optional[float] = Field(None, ge=0, le=1)
    navigation_session_id: Optional[str] = None
    navigation_status: Optional[str] = None
    selected_route_index: Optional[int] = None
    alternative_routes: Optional[Any] = None
    route_polyline: Optional[Any] = None
    route_steps: Optional[Any] = None
    matched_risk_points: Optional[Any] = None
    current_step_index: Optional[int] = None
    distance_to_next_action_m: Optional[float] = None
    distance_to_route_m: Optional[float] = None
    off_route: Optional[bool] = None
    arrived: Optional[bool] = None
    road_segment_id: Optional[int] = None
    road_risk_score: Optional[float] = None
    road_risk_confidence: Optional[float] = None
    safe_traversal_count: Optional[int] = None
    pitch_deg: Optional[float] = None
    roll_deg: Optional[float] = None
    alert_type: Optional[str] = None
    location_quality: Optional[str] = None
    location_provider: Optional[str] = None
    source: str = "esp32c5"
    button_event: Optional[str] = Field(None, pattern="^(short_press|double_click|long_press|sos)$")
    touch_electrode: Optional[int] = Field(None, ge=0, le=11)
    touch_event: Optional[str] = Field(None, pattern="^(tap|double_click|long_press)$")
    manual_risk_type: Optional[str] = None
    extra: Optional[Any] = None
    timestamp: Optional[str] = None


class MapRouteRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    origin_lat: Optional[float] = Field(None, ge=-90, le=90)
    origin_lng: Optional[float] = Field(None, ge=-180, le=180)
    destination_lat: Optional[float] = Field(None, ge=-90, le=90)
    destination_lng: Optional[float] = Field(None, ge=-180, le=180)
    origin_text: Optional[str] = None
    destination_text: Optional[str] = None
    city: Optional[str] = None
    coordsys: str = "gps"
    risk_radius_m: float = Field(80.0, gt=0, le=5000)
    route_buffer_m: float = Field(ROUTE_RISK_BUFFER_M, gt=5, le=200)
    route_preference: str = Field("safe", pattern="^(safe|distance)$")
    sensor_frame: Optional[SensorFrameCreate] = None


class VoiceRouteRequest(BaseModel):
    device_id: str = Field(..., min_length=1)
    text: str = Field(..., min_length=1)
    current_lat: Optional[float] = Field(None, ge=-90, le=90)
    current_lng: Optional[float] = Field(None, ge=-180, le=180)
    city: Optional[str] = None
    coordsys: str = "gps"
    user_id: Optional[str] = None
    route_preference: str = Field("safe", pattern="^(safe|distance)$")


class NavigationSessionUpdate(BaseModel):
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)
    accuracy_m: Optional[float] = None
    status: Optional[str] = None
    distance_delta_m: Optional[float] = Field(None, ge=0)
    risk_event_count_delta: int = Field(0, ge=0)
    safe_pass: Optional[bool] = None


class DeviceCommandCreate(BaseModel):
    device_id: str = Field(..., min_length=1)
    command: str = Field(..., pattern="^(cancel_fall)$")
    source: str = Field("android", min_length=1)


class LegacySosCreate(BaseModel):
    deviceId: str = Field(..., min_length=1)
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    message: str = "\u7528\u6237\u901a\u8fc7 Android App \u53d1\u8d77\u7d27\u6025\u6c42\u52a9"


class LegacyTelemetryCreate(BaseModel):
    deviceId: str = Field(..., min_length=1)
    battery: Optional[int] = Field(None, ge=0, le=100)
    frontDistanceMm: Optional[int] = Field(None, ge=0)
    leftDistanceMm: Optional[int] = Field(None, ge=0)
    rightDistanceMm: Optional[int] = Field(None, ge=0)
    downDistanceMm: Optional[int] = Field(None, ge=0)
    latitude: Optional[float] = Field(None, ge=-90, le=90)
    longitude: Optional[float] = Field(None, ge=-180, le=180)
    timestamp: Optional[str] = None


class PairingCodeCreate(BaseModel):
    blindUserId: str = Field(..., min_length=1)
    deviceId: str = Field(..., min_length=1)


class AuthLoginRequest(BaseModel):
    account: str = Field(..., min_length=1)
    password: str = Field(..., min_length=1)


class AuthRegisterRequest(BaseModel):
    account: str = Field(..., min_length=3, max_length=40)
    password: str = Field(..., min_length=6, max_length=100)
    displayName: str = Field(..., min_length=1, max_length=40)
    role: str = Field("blind", pattern="^(blind|companion)$")


class CareRelationRequestCreate(BaseModel):
    code: str = Field(..., min_length=6, max_length=6)
    companionUserId: str = Field(..., min_length=1)
    companionName: str = Field(..., min_length=1)


app = FastAPI(title="Smart Cane Collaborative Risk Backend", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def add_no_cache_headers(request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/") or request.url.path == "/status":
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def secret_env(name: str, default: str = "") -> str:
    value = env(name, default)
    if value.lower() in {"replace_me", "changeme", "your_api_key", "your-api-key", "none", "null"}:
        return ""
    return value


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def mm_to_cm(value: Optional[int]) -> Optional[int]:
    if value is None:
        return None
    return int(round(value / 10))


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
                direction TEXT,
                sensor TEXT,
                distance_mm INTEGER,
                battery REAL,
                front_cm INTEGER,
                left_cm INTEGER,
                right_cm INTEGER,
                down_cm INTEGER,
                risk_score REAL,
                confidence REAL,
                fall_event_id TEXT,
                voice_prompt TEXT,
                feedback_json TEXT,
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
                provider TEXT,
                quality TEXT,
                accuracy_m REAL,
                hdop REAL,
                fix_quality INTEGER,
                satellite_count INTEGER
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS risk_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                status TEXT NOT NULL DEFAULT 'active',
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                risk_type TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.5,
                report_count INTEGER NOT NULL DEFAULT 1,
                source_devices_json TEXT NOT NULL DEFAULT '[]',
                first_reported_at TEXT NOT NULL,
                last_reported_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                latest_event_id INTEGER,
                voice_prompt TEXT,
                message TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_state (
                device_id TEXT PRIMARY KEY,
                device_name TEXT,
                updated_at TEXT NOT NULL,
                online INTEGER NOT NULL DEFAULT 1,
                lat REAL,
                lng REAL,
                battery REAL,
                front_cm INTEGER,
                left_cm INTEGER,
                right_cm INTEGER,
                down_cm INTEGER,
                heading_deg REAL,
                risk_type TEXT,
                risk_level TEXT,
                risk_score REAL,
                voice_prompt TEXT,
                source TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                account TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                role TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS pairing_codes (
                code TEXT PRIMARY KEY,
                blind_user_id TEXT NOT NULL,
                blind_name TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                status TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS care_requests (
                request_id TEXT PRIMARY KEY,
                code TEXT NOT NULL,
                status TEXT NOT NULL,
                blind_user_id TEXT NOT NULL,
                blind_name TEXT NOT NULL,
                companion_user_id TEXT NOT NULL,
                companion_name TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS care_relations (
                relation_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                blind_user_id TEXT NOT NULL,
                blind_name TEXT NOT NULL,
                companion_user_id TEXT NOT NULL,
                companion_name TEXT NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS navigation_sessions (
                session_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                user_id TEXT,
                origin_lat REAL NOT NULL,
                origin_lng REAL NOT NULL,
                destination_lat REAL NOT NULL,
                destination_lng REAL NOT NULL,
                destination_text TEXT,
                route_polyline_json TEXT NOT NULL,
                route_steps_json TEXT NOT NULL,
                current_step_index INTEGER NOT NULL DEFAULT 0,
                started_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                status TEXT NOT NULL,
                last_lat REAL,
                last_lng REAL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS road_segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                road_name TEXT NOT NULL,
                start_lat REAL NOT NULL,
                start_lng REAL NOT NULL,
                end_lat REAL NOT NULL,
                end_lng REAL NOT NULL,
                polyline_json TEXT NOT NULL,
                length_m REAL NOT NULL,
                city TEXT,
                district TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS road_risk_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                road_segment_id INTEGER,
                device_id TEXT NOT NULL,
                risk_type TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.5,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                location_accuracy_m REAL,
                source TEXT,
                is_fixed INTEGER NOT NULL DEFAULT 0,
                observed_at TEXT NOT NULL,
                expires_at TEXT,
                match_status TEXT NOT NULL DEFAULT 'pending'
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS road_traversals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                road_segment_id INTEGER NOT NULL,
                device_id TEXT NOT NULL,
                navigation_session_id TEXT,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                duration_seconds REAL,
                distance_m REAL NOT NULL DEFAULT 0,
                safe_pass INTEGER NOT NULL DEFAULT 1,
                risk_event_count INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'active'
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS road_risk_scores (
                road_segment_id INTEGER PRIMARY KEY,
                risk_score REAL NOT NULL,
                confidence_score REAL NOT NULL,
                event_density_per_km REAL NOT NULL,
                unique_device_count INTEGER NOT NULL,
                safe_traversal_count INTEGER NOT NULL,
                high_count INTEGER NOT NULL,
                medium_count INTEGER NOT NULL,
                low_count INTEGER NOT NULL,
                main_risk_type TEXT,
                calculated_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_commands (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                command TEXT NOT NULL,
                source TEXT NOT NULL,
                created_at TEXT NOT NULL,
                delivered_at TEXT,
                status TEXT NOT NULL DEFAULT 'pending'
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS device_alert_suppression (
                device_id TEXT PRIMARY KEY,
                fall_event_id TEXT,
                until_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_events_lat_lng ON risk_events(lat, lng)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_events_level ON risk_events(risk_level)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_device_locations_device ON device_locations(device_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_points_status ON risk_points(status, expires_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_risk_points_lat_lng ON risk_points(lat, lng)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_device_state_updated ON device_state(updated_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_users_account ON users(account)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_care_requests_blind ON care_requests(blind_user_id, status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_care_requests_companion ON care_requests(companion_user_id, status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_care_relations_blind ON care_relations(blind_user_id, status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_care_relations_companion ON care_relations(companion_user_id, status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_navigation_sessions_device ON navigation_sessions(device_id, status)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_road_segments_name ON road_segments(road_name)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_road_observations_segment ON road_risk_observations(road_segment_id, observed_at)")
        ensure_column(conn, "risk_events", "direction", "TEXT")
        ensure_column(conn, "risk_events", "sensor", "TEXT")
        ensure_column(conn, "risk_events", "distance_mm", "INTEGER")
        ensure_column(conn, "risk_events", "battery", "REAL")
        ensure_column(conn, "risk_events", "risk_score", "REAL")
        ensure_column(conn, "risk_events", "voice_prompt", "TEXT")
        ensure_column(conn, "risk_events", "feedback_json", "TEXT")
        ensure_column(conn, "device_locations", "provider", "TEXT")
        ensure_column(conn, "device_locations", "quality", "TEXT")
        ensure_column(conn, "device_locations", "hdop", "REAL")
        ensure_column(conn, "device_locations", "fix_quality", "INTEGER")
        ensure_column(conn, "risk_points", "confidence", "REAL")
        ensure_column(conn, "risk_points", "report_count", "INTEGER")
        ensure_column(conn, "risk_points", "source_devices_json", "TEXT")
        ensure_column(conn, "risk_points", "expires_at", "TEXT")
        ensure_column(conn, "risk_points", "latest_event_id", "INTEGER")
        ensure_column(conn, "risk_points", "voice_prompt", "TEXT")
        ensure_column(conn, "risk_points", "message", "TEXT")
        ensure_column(conn, "device_state", "device_name", "TEXT")
        ensure_column(conn, "device_state", "heading_deg", "REAL")
        ensure_column(conn, "device_state", "risk_score", "REAL")
        ensure_column(conn, "device_state", "voice_prompt", "TEXT")
        ensure_column(conn, "device_state", "source", "TEXT")
        ensure_column(conn, "users", "account", "TEXT")
        ensure_column(conn, "users", "display_name", "TEXT")
        ensure_column(conn, "users", "role", "TEXT")
        ensure_column(conn, "users", "password_salt", "TEXT")
        ensure_column(conn, "users", "password_hash", "TEXT")
        ensure_column(conn, "road_risk_observations", "match_status", "TEXT NOT NULL DEFAULT 'matched'")
        ensure_column(conn, "road_risk_observations", "matched_distance_m", "REAL")
        ensure_column(conn, "road_segments", "road_segment_key", "TEXT")
        ensure_column(conn, "road_traversals", "distance_m", "REAL NOT NULL DEFAULT 0")
        ensure_column(conn, "road_traversals", "status", "TEXT NOT NULL DEFAULT 'active'")
        ensure_column(conn, "navigation_sessions", "off_route_count", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "navigation_sessions", "arrival_count", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "navigation_sessions", "active_traversal_id", "INTEGER")
        ensure_column(conn, "navigation_sessions", "route_preference", "TEXT NOT NULL DEFAULT 'safe'")
        ensure_column(conn, "navigation_sessions", "destination_coordsys", "TEXT NOT NULL DEFAULT 'gps'")
        ensure_column(conn, "device_state", "fall_event_id", "TEXT")
        ensure_column(conn, "device_state", "fall_pending", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "device_state", "fall_detected", "INTEGER NOT NULL DEFAULT 0")
        ensure_column(conn, "device_state", "fall_stage", "TEXT")
        ensure_column(conn, "device_state", "fall_confidence", "REAL")
        ensure_column(conn, "risk_events", "fall_event_id", "TEXT")
        ensure_column(conn, "risk_events", "confidence", "REAL")
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_road_segments_key ON road_segments(road_segment_key)")
        # Keep the first copy left by older builds, then make fall de-duplication
        # durable across API processes and restarts.
        conn.execute(
            """
            DELETE FROM risk_events
             WHERE fall_event_id IS NOT NULL AND TRIM(fall_event_id) <> ''
               AND id NOT IN (
                   SELECT MIN(id) FROM risk_events
                    WHERE fall_event_id IS NOT NULL AND TRIM(fall_event_id) <> ''
                    GROUP BY fall_event_id
               )
            """
        )
        conn.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_risk_events_fall_event_id
                ON risk_events(fall_event_id)
             WHERE fall_event_id IS NOT NULL AND TRIM(fall_event_id) <> ''
            """
        )
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_account ON users(account)")


def ensure_column(conn: sqlite3.Connection, table: str, column: str, column_type: str) -> None:
    columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
    if column not in columns:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {column_type}")


def row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def event_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    item = row_to_dict(row)
    item["level"] = item.get("risk_level")
    return item


def normalize_extra(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def normalize_account(account: str) -> str:
    return account.strip().lower()


def hash_password(password: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{password}".encode("utf-8")).hexdigest()


def public_user(row: sqlite3.Row) -> dict[str, Any]:
    item = row_to_dict(row)
    return {
        "userId": item["user_id"],
        "account": item["account"],
        "displayName": item["display_name"],
        "role": item["role"],
    }


def device_state_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    item = row_to_dict(row)
    last_seen = parse_time(item.get("updated_at"))
    online = bool(last_seen and (datetime.now(timezone.utc) - last_seen).total_seconds() <= DEVICE_OFFLINE_SECONDS)
    return {
        "deviceId": item["device_id"],
        "deviceName": item.get("device_name") or item["device_id"],
        "device_name": item.get("device_name") or item["device_id"],
        "updatedAt": item.get("updated_at"),
        "online": online,
        "latitude": item.get("lat"),
        "longitude": item.get("lng"),
        "battery": item.get("battery"),
        "frontCm": item.get("front_cm"),
        "leftCm": item.get("left_cm"),
        "rightCm": item.get("right_cm"),
        "downCm": item.get("down_cm"),
        "headingDeg": item.get("heading_deg"),
        "riskType": item.get("risk_type") or "none",
        "riskLevel": item.get("risk_level") or "low",
        "riskScore": item.get("risk_score") or 0,
        "voicePrompt": item.get("voice_prompt") or "当前未发现明显风险",
        "source": item.get("source") or "unknown",
        "fallEventId": item.get("fall_event_id"),
        "fallPending": bool(item.get("fall_pending")),
        "fallDetected": bool(item.get("fall_detected")),
        "fallStage": item.get("fall_stage"),
        "fallConfidence": item.get("fall_confidence"),
    }


def upsert_device_state(frame: SensorFrameCreate, lat: float, lng: float, analysis: dict[str, Any]) -> dict[str, Any]:
    timestamp = frame.timestamp or now_iso()
    with db() as conn:
        conn.execute(
            """
            INSERT INTO device_state (
                device_id, device_name, updated_at, online, lat, lng, battery,
                front_cm, left_cm, right_cm, down_cm, heading_deg,
                risk_type, risk_level, risk_score, voice_prompt, source,
                fall_event_id, fall_pending, fall_detected, fall_stage, fall_confidence
            ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_name = COALESCE(NULLIF(excluded.device_name, ''), device_state.device_name),
                updated_at = excluded.updated_at,
                online = 1,
                lat = excluded.lat,
                lng = excluded.lng,
                battery = excluded.battery,
                front_cm = excluded.front_cm,
                left_cm = excluded.left_cm,
                right_cm = excluded.right_cm,
                down_cm = excluded.down_cm,
                heading_deg = excluded.heading_deg,
                risk_type = excluded.risk_type,
                risk_level = excluded.risk_level,
                risk_score = excluded.risk_score,
                voice_prompt = excluded.voice_prompt,
                source = excluded.source,
                fall_event_id = excluded.fall_event_id,
                fall_pending = excluded.fall_pending,
                fall_detected = excluded.fall_detected,
                fall_stage = excluded.fall_stage,
                fall_confidence = excluded.fall_confidence
            """,
            (
                frame.device_id,
                frame.device_name,
                timestamp,
                lat,
                lng,
                frame.battery,
                frame.front_cm,
                frame.left_cm,
                frame.right_cm,
                frame.down_cm,
                frame.heading_deg,
                analysis.get("risk_type") or "none",
                analysis.get("risk_level") or "low",
                analysis.get("risk_score") or 0,
                analysis.get("voice_prompt"),
                frame.source,
                frame.fall_event_id,
                1 if frame.fall_pending else 0,
                1 if frame.fall_detected else 0,
                frame.fall_stage,
                frame.fall_confidence,
            ),
        )
        row = conn.execute("SELECT * FROM device_state WHERE device_id = ?", (frame.device_id,)).fetchone()
    return device_state_to_dict(row)


def upsert_device_state_from_event(event: dict[str, Any]) -> Optional[dict[str, Any]]:
    device_id = str(event.get("device_id") or "").strip()
    if not device_id:
        return None
    timestamp = str(event.get("timestamp") or now_iso())
    source = str(event.get("source") or event.get("sensor") or "risk_event")
    with db() as conn:
        conn.execute(
            """
            INSERT INTO device_state (
                device_id, device_name, updated_at, online, lat, lng, battery,
                front_cm, left_cm, right_cm, down_cm, heading_deg,
                risk_type, risk_level, risk_score, voice_prompt, source
            ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                device_name = COALESCE(NULLIF(excluded.device_name, ''), device_state.device_name),
                updated_at = excluded.updated_at,
                online = 1,
                lat = excluded.lat,
                lng = excluded.lng,
                battery = COALESCE(excluded.battery, device_state.battery),
                front_cm = COALESCE(excluded.front_cm, device_state.front_cm),
                left_cm = COALESCE(excluded.left_cm, device_state.left_cm),
                right_cm = COALESCE(excluded.right_cm, device_state.right_cm),
                down_cm = COALESCE(excluded.down_cm, device_state.down_cm),
                risk_type = excluded.risk_type,
                risk_level = excluded.risk_level,
                risk_score = COALESCE(excluded.risk_score, device_state.risk_score),
                voice_prompt = COALESCE(excluded.voice_prompt, device_state.voice_prompt),
                source = excluded.source
            """,
            (
                device_id,
                event.get("device_name") or event.get("deviceName"),
                timestamp,
                event.get("lat"),
                event.get("lng"),
                event.get("battery"),
                event.get("front_cm") if event.get("front_cm") is not None else event.get("frontCm"),
                event.get("left_cm") if event.get("left_cm") is not None else event.get("leftCm"),
                event.get("right_cm") if event.get("right_cm") is not None else event.get("rightCm"),
                event.get("down_cm") if event.get("down_cm") is not None else event.get("downCm"),
                event.get("risk_type") or event.get("riskType") or "none",
                event.get("risk_level") or event.get("riskLevel") or event.get("level") or "low",
                event.get("risk_score") if event.get("risk_score") is not None else event.get("riskScore"),
                event.get("voice_prompt") or event.get("voicePrompt") or legacy_event_message(event),
                source,
            ),
        )
        row = conn.execute("SELECT * FROM device_state WHERE device_id = ?", (device_id,)).fetchone()
    return device_state_to_dict(row) if row else None


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
        item = event_to_dict(row)
        if is_legacy_sim_point(float(item["lat"]), float(item["lng"])):
            continue
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


def parse_time(value: str | None) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def bearing_between_deg(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_lam = math.radians(lng2 - lng1)
    y = math.sin(d_lam) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lam)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def angle_delta_deg(a: float, b: float) -> float:
    return (a - b + 180.0) % 360.0 - 180.0


def relative_direction(delta_deg: Optional[float]) -> str:
    if delta_deg is None:
        return "front"
    abs_delta = abs(delta_deg)
    if abs_delta <= 30:
        return "front"
    if abs_delta <= 75:
        return "right_front" if delta_deg > 0 else "left_front"
    if abs_delta <= 120:
        return "right" if delta_deg > 0 else "left"
    return "behind"


def relative_direction_label(direction: str) -> str:
    return {
        "front": "\u524d\u65b9",
        "left_front": "左前方",
        "right_front": "右前方",
        "left": "\u5de6\u4fa7",
        "right": "\u53f3\u4fa7",
        "behind": "\u540e\u65b9",
    }.get(direction, "\u524d\u65b9")


def event_distance_mm(item: dict[str, Any]) -> Optional[int]:
    if item.get("distance_mm") is not None:
        return int(item["distance_mm"])
    risk_type = str(item.get("risk_type") or "")
    if "front" in risk_type and item.get("front_cm") is not None:
        return int(item["front_cm"]) * 10
    if "left" in risk_type and item.get("left_cm") is not None:
        return int(item["left_cm"]) * 10
    if "right" in risk_type and item.get("right_cm") is not None:
        return int(item["right_cm"]) * 10
    if ("ground" in risk_type or "drop" in risk_type) and item.get("down_cm") is not None:
        return int(item["down_cm"]) * 10
    return None


def extra_message(item: dict[str, Any]) -> Optional[str]:
    raw = item.get("extra_json")
    if not raw:
        return None
    if isinstance(raw, dict):
        message = raw.get("message")
        return str(message) if message else None
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return raw if raw.startswith("message=") else None
        if isinstance(parsed, dict) and parsed.get("message"):
            return str(parsed["message"])
    return None


def legacy_event_message(item: dict[str, Any]) -> str:
    custom_message = extra_message(item)
    if custom_message:
        return custom_message.removeprefix("message=")

    risk_type = str(item.get("risk_type") or "none")
    distance = event_distance_mm(item)
    cm_text = f"{int(round(distance / 10))} \u5398\u7c73" if distance is not None else "\u672a\u77e5\u8ddd\u79bb"
    if risk_type == "sos":
        return "\u6536\u5230 Android App \u7d27\u6025\u6c42\u52a9\uff0c\u8bf7\u5c3d\u5feb\u8054\u7cfb\u4f7f\u7528\u8005\u3002"
    if risk_type == "fall_detected":
        return "\u68c0\u6d4b\u5230\u7591\u4f3c\u8dcc\u5012\uff0c\u5df2\u5411\u76f2\u4eba\u7aef\u548c\u966a\u62a4\u7aef\u53d1\u9001\u7d27\u6025\u544a\u8b66\u3002"
    if risk_type == "voice_request":
        return "\u76f2\u6756\u6309\u94ae\u5df2\u89e6\u53d1\u8bed\u97f3\u4ea4\u4e92\uff0c\u8bf7\u5728\u76f2\u4eba\u7aef\u8bf4\u51fa\u76ee\u7684\u5730\u6216\u6307\u4ee4\u3002"
    if risk_type == "prolonged_obstacle":
        return "\u540c\u4e00\u969c\u788d\u6301\u7eed\u51fa\u73b0\uff0c\u5efa\u8bae\u966a\u62a4\u8005\u5173\u6ce8\u4f7f\u7528\u8005\u4f4d\u7f6e\u548c\u72b6\u6001\u3002"
    if risk_type == "approaching_obstacle":
        return "\u524d\u65b9\u969c\u788d\u8ddd\u79bb\u6b63\u5728\u6301\u7eed\u7f29\u77ed\uff0c\u5efa\u8bae\u51cf\u901f\u6216\u505c\u6b62\u786e\u8ba4\u3002"
    if risk_type == "ground_drop":
        return f"\u4e0b\u89c6\u8ddd\u79bb\u7ea6 {cm_text}\uff0c\u53ef\u80fd\u6709\u53f0\u9636\u3001\u5751\u6d3c\u6216\u843d\u5dee\uff0c\u8bf7\u505c\u6b62\u524d\u8fdb\u3002"
    if risk_type == "front_obstacle":
        return f"\u524d\u65b9\u7ea6 {cm_text} \u6709\u969c\u788d\uff0c\u8bf7\u51cf\u901f\u5e76\u51c6\u5907\u7ed5\u884c\u3002"
    if risk_type == "left_obstacle":
        return f"\u5de6\u4fa7\u7ea6 {cm_text} \u6709\u969c\u788d\uff0c\u8bf7\u5411\u53f3\u4fa7\u4fdd\u6301\u8ddd\u79bb\u3002"
    if risk_type == "right_obstacle":
        return f"\u53f3\u4fa7\u7ea6 {cm_text} \u6709\u969c\u788d\uff0c\u8bf7\u5411\u5de6\u4fa7\u4fdd\u6301\u8ddd\u79bb\u3002"
    if risk_type == "user_mark":
        return "\u7528\u6237\u624b\u52a8\u6807\u8bb0\u4e86\u4e00\u4e2a\u98ce\u9669\u70b9\u3002"
    if risk_type == "history_risk":
        return "\u9644\u8fd1\u5b58\u5728\u5386\u53f2\u9ad8\u98ce\u9669\u70b9\uff0c\u8bf7\u51cf\u901f\u786e\u8ba4\u3002"
    return "\u6682\u65e0\u660e\u786e\u98ce\u9669\uff0c\u8bf7\u4fdd\u6301\u8c28\u614e\u3002"


def legacy_event_dict(row: sqlite3.Row) -> dict[str, Any]:
    item = event_to_dict(row)
    feedback = None
    if item.get("feedback_json"):
        try:
            feedback = json.loads(item["feedback_json"])
        except json.JSONDecodeError:
            feedback = item["feedback_json"]
    return {
        "id": item["id"],
        "deviceId": item["device_id"],
        "riskType": item["risk_type"],
        "riskLevel": item["risk_level"],
        "distance": event_distance_mm(item),
        "message": legacy_event_message(item),
        "voicePrompt": item.get("voice_prompt") or legacy_event_message(item),
        "riskScore": item.get("risk_score"),
        "feedback": feedback,
        "latitude": item.get("lat"),
        "longitude": item.get("lng"),
        "timestamp": item.get("timestamp"),
    }


def parse_json_field(value: Any) -> Any:
    if value is None or value == "":
        return None
    if isinstance(value, (dict, list)):
        return value
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


def mobile_event_dict(row: sqlite3.Row) -> dict[str, Any]:
    item = event_to_dict(row)
    feedback = parse_json_field(item.get("feedback_json")) or {}
    extra = parse_json_field(item.get("extra_json")) or {}
    if not isinstance(feedback, dict):
        feedback = {"raw": feedback}
    if not isinstance(extra, dict):
        extra = {"raw": extra}

    risk_type = str(item.get("risk_type") or "none")
    risk_level = str(item.get("risk_level") or item.get("level") or "low")
    distance_mm = event_distance_mm(item)
    voice_prompt = item.get("voice_prompt") or legacy_event_message(item)
    feedback_vibration = feedback.get("vibration") if isinstance(feedback.get("vibration"), dict) else {}
    feedback_buzzer = feedback.get("buzzer") if isinstance(feedback.get("buzzer"), dict) else {}
    sensor_line = (
        f"[SENSOR] front={item.get('front_cm')}cm left={item.get('left_cm')}cm "
        f"right={item.get('right_cm')}cm down={item.get('down_cm')}cm"
    )
    risk_line = (
        f"[RISK] risk={risk_level} type={risk_type} "
        f"direction={item.get('direction') or 'none'} sensor={item.get('sensor') or 'unknown'} "
        f"score={item.get('risk_score')}"
    )

    return {
        "id": item.get("id"),
        "device_id": item.get("device_id"),
        "deviceId": item.get("device_id"),
        "timestamp": item.get("timestamp"),
        "level": risk_level,
        "risk_level": risk_level,
        "riskLevel": risk_level,
        "risk_type": risk_type,
        "riskType": risk_type,
        "title": alert_title(risk_type),
        "direction": item.get("direction") or "none",
        "sensor": item.get("sensor") or "unknown",
        "risk_score": item.get("risk_score"),
        "riskScore": item.get("risk_score"),
        "message": legacy_event_message(item),
        "voice_prompt": voice_prompt,
        "voicePrompt": voice_prompt,
        "summary": f"{sensor_line}\n{risk_line}",
        "sensorLine": sensor_line,
        "riskLine": risk_line,
        "distance_mm": distance_mm,
        "distanceMm": distance_mm,
        "distance": distance_mm,
        "front_cm": item.get("front_cm"),
        "left_cm": item.get("left_cm"),
        "right_cm": item.get("right_cm"),
        "down_cm": item.get("down_cm"),
        "frontCm": item.get("front_cm"),
        "leftCm": item.get("left_cm"),
        "rightCm": item.get("right_cm"),
        "downCm": item.get("down_cm"),
        "distances_cm": {
            "front": item.get("front_cm"),
            "left": item.get("left_cm"),
            "right": item.get("right_cm"),
            "down": item.get("down_cm"),
        },
        "distancesCm": {
            "front": item.get("front_cm"),
            "left": item.get("left_cm"),
            "right": item.get("right_cm"),
            "down": item.get("down_cm"),
        },
        "location": {
            "lat": item.get("lat"),
            "lng": item.get("lng"),
        },
        "latitude": item.get("lat"),
        "longitude": item.get("lng"),
        "feedback": {
            "buzzer": feedback_buzzer,
            "vibration": feedback_vibration,
            "action": feedback.get("action"),
        },
        "imu": {
            "fall_detected": bool(extra.get("fall_detected")),
            "fallDetected": bool(extra.get("fall_detected")),
            "fall_stage": extra.get("fall_stage"),
            "fallStage": extra.get("fall_stage"),
            "fall_confidence": extra.get("fall_confidence"),
            "fallConfidence": extra.get("fall_confidence"),
            "accel_total_g": extra.get("accel_total_g"),
            "accelTotalG": extra.get("accel_total_g"),
        },
        "input": {
            "button_event": extra.get("button_event"),
            "buttonEvent": extra.get("button_event"),
            "touch_electrode": extra.get("touch_electrode"),
            "touchElectrode": extra.get("touch_electrode"),
            "touch_event": extra.get("touch_event"),
            "touchEvent": extra.get("touch_event"),
        },
        "alert": {
            "priority": alert_priority(risk_type, risk_level),
            "target_roles": alert_target_roles(risk_type),
            "targetRoles": alert_target_roles(risk_type),
            "requires_attention": risk_type in ALERT_RISK_TYPES or risk_level == "high",
            "requiresAttention": risk_type in ALERT_RISK_TYPES or risk_level == "high",
        },
        "nearby_history": extra.get("nearby_history"),
        "nearbyHistory": extra.get("nearby_history"),
    }


ALERT_RISK_TYPES = {"sos", "fall_detected", "voice_request", "prolonged_obstacle", "approaching_obstacle"}


def alert_priority(risk_type: str, level: str) -> str:
    if risk_type in {"sos", "fall_detected"}:
        return "critical"
    if risk_type == "voice_request":
        return "info"
    if level == "high":
        return "high"
    return "medium"


def allowed_alert_devices(role: str, user_id: Optional[str], device_id: Optional[str]) -> Optional[set[str]]:
    if device_id:
        return {device_id}
    if not user_id:
        return None
    field = "companion_user_id" if role == "companion" else "blind_user_id"
    with db() as conn:
        rows = conn.execute(
            f"SELECT device_id FROM care_relations WHERE {field} = ? AND status = 'active'",
            (user_id,),
        ).fetchall()
    devices = {str(row["device_id"]) for row in rows}
    return devices


def alert_target_roles(risk_type: str) -> list[str]:
    if risk_type in {"sos", "fall_detected"}:
        return ["blind", "companion"]
    if risk_type == "voice_request":
        return ["blind"]
    return ["companion"]


def alert_event_payload(row: sqlite3.Row, role: str) -> dict[str, Any]:
    event = mobile_event_dict(row)
    risk_type = event["riskType"]
    level = event["riskLevel"]
    return {
        "id": event["id"],
        "deviceId": event["deviceId"],
        "riskType": risk_type,
        "riskLevel": level,
        "priority": alert_priority(risk_type, level),
        "title": alert_title(risk_type),
        "message": event["message"],
        "voicePrompt": event["voicePrompt"],
        "latitude": event["latitude"],
        "longitude": event["longitude"],
        "timestamp": event["timestamp"],
        "targetRoles": alert_target_roles(risk_type),
        "forRole": role,
        "requiresAttention": True,
        "feedback": event.get("feedback"),
        "riskScore": event.get("riskScore"),
        "distance": event.get("distance"),
        "distancesCm": event.get("distancesCm"),
        "imu": event.get("imu"),
        "input": event.get("input"),
    }


MAPPABLE_RISK_TYPES = {
    "front_obstacle",
    "left_obstacle",
    "right_obstacle",
    "ground_drop",
    "ground_step",
    "down_obstacle",
    "down_no_target",
    "down_sensor_unavailable",
    "user_mark",
    "history_risk",
    "prolonged_obstacle",
    "approaching_obstacle",
}


def risk_point_ttl_seconds(risk_type: str, level: str) -> int:
    if risk_type in {"ground_drop", "ground_step", "user_mark", "history_risk"}:
        return RISK_POINT_FIXED_TTL_SECONDS
    if risk_type in {"sos", "fall_detected"}:
        return RISK_POINT_EMERGENCY_TTL_SECONDS
    if level == "high":
        return max(RISK_POINT_TRANSIENT_TTL_SECONDS, 4 * 60 * 60)
    return RISK_POINT_TRANSIENT_TTL_SECONDS


def parse_devices_json(raw: Any) -> list[str]:
    if not raw:
        return []
    try:
        value = json.loads(str(raw))
    except json.JSONDecodeError:
        return []
    return [str(item) for item in value] if isinstance(value, list) else []


def risk_point_confidence(level: str, report_count: int, source_count: int) -> float:
    level_bonus = {"high": 0.18, "medium": 0.08, "low": 0.0}.get(level, 0.0)
    report_bonus = min(report_count, 8) * 0.055
    source_bonus = min(source_count, 5) * 0.045
    return round(min(0.97, 0.32 + level_bonus + report_bonus + source_bonus), 2)


def is_legacy_sim_point(lat: float, lng: float) -> bool:
    return haversine_m(lat, lng, LEGACY_SIM_POINT_LAT, LEGACY_SIM_POINT_LNG) <= LEGACY_SIM_POINT_RADIUS_M


def expire_risk_points() -> None:
    with db() as conn:
        conn.execute(
            "UPDATE risk_points SET status = 'expired' WHERE status = 'active' AND expires_at < ?",
            (now_iso(),),
        )
        conn.execute(
            "UPDATE risk_points SET status = 'expired' WHERE status = 'active' AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?",
            (
                LEGACY_SIM_POINT_LAT - 0.001,
                LEGACY_SIM_POINT_LAT + 0.001,
                LEGACY_SIM_POINT_LNG - 0.001,
                LEGACY_SIM_POINT_LNG + 0.001,
            ),
        )


def risk_point_message_from_event(event: dict[str, Any]) -> str:
    return str(event.get("voice_prompt") or legacy_event_message(event) or "风险点")


def upsert_risk_point_for_event(event: dict[str, Any]) -> None:
    risk_type = str(event.get("risk_type") or "none")
    level = str(event.get("risk_level") or "low")
    if risk_type not in MAPPABLE_RISK_TYPES or level not in LEVEL_RANK:
        return
    lat = event.get("lat")
    lng = event.get("lng")
    if lat is None or lng is None:
        return
    lat = float(lat)
    lng = float(lng)
    if abs(lat) < 1e-9 and abs(lng) < 1e-9:
        return
    if is_legacy_sim_point(lat, lng):
        return

    now = event.get("timestamp") or now_iso()
    expires_at = (datetime.now(timezone.utc) + timedelta(seconds=risk_point_ttl_seconds(risk_type, level))).isoformat(timespec="seconds")
    message = risk_point_message_from_event(event)
    device_id = str(event.get("device_id") or "unknown")

    expire_risk_points()
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM risk_points WHERE status = 'active' AND risk_type = ?",
            (risk_type,),
        ).fetchall()
        best_row = None
        best_distance = None
        for row in rows:
            distance = haversine_m(lat, lng, float(row["lat"]), float(row["lng"]))
            if distance <= RISK_POINT_CLUSTER_RADIUS_M and (best_distance is None or distance < best_distance):
                best_row = row
                best_distance = distance

        if best_row:
            point = row_to_dict(best_row)
            report_count = int(point.get("report_count") or 1) + 1
            devices = sorted(set(parse_devices_json(point.get("source_devices_json"))) | {device_id})
            merged_lat = (float(point["lat"]) * (report_count - 1) + lat) / report_count
            merged_lng = (float(point["lng"]) * (report_count - 1) + lng) / report_count
            merged_level = level if LEVEL_RANK[level] >= LEVEL_RANK.get(str(point.get("risk_level") or "low"), 0) else str(point.get("risk_level") or level)
            confidence = risk_point_confidence(merged_level, report_count, len(devices))
            conn.execute(
                """
                UPDATE risk_points
                SET lat = ?, lng = ?, risk_level = ?, confidence = ?, report_count = ?,
                    source_devices_json = ?, last_reported_at = ?, expires_at = ?,
                    latest_event_id = ?, voice_prompt = ?, message = ?
                WHERE id = ?
                """,
                (
                    merged_lat,
                    merged_lng,
                    merged_level,
                    confidence,
                    report_count,
                    json.dumps(devices, ensure_ascii=False),
                    now,
                    expires_at,
                    event.get("id"),
                    event.get("voice_prompt"),
                    message,
                    point["id"],
                ),
            )
        else:
            devices = [device_id]
            confidence = risk_point_confidence(level, 1, 1)
            conn.execute(
                """
                INSERT INTO risk_points (
                    status, lat, lng, risk_type, risk_level, confidence, report_count,
                    source_devices_json, first_reported_at, last_reported_at, expires_at,
                    latest_event_id, voice_prompt, message
                ) VALUES ('active', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    lat,
                    lng,
                    risk_type,
                    level,
                    confidence,
                    1,
                    json.dumps(devices, ensure_ascii=False),
                    now,
                    now,
                    expires_at,
                    event.get("id"),
                    event.get("voice_prompt"),
                    message,
                ),
            )


def risk_point_to_event_dict(row: sqlite3.Row, origin_lat: Optional[float] = None, origin_lng: Optional[float] = None) -> dict[str, Any]:
    point = row_to_dict(row)
    devices = parse_devices_json(point.get("source_devices_json"))
    distance_m = None
    if origin_lat is not None and origin_lng is not None:
        distance_m = round(haversine_m(origin_lat, origin_lng, float(point["lat"]), float(point["lng"])), 1)
    message = point.get("message") or point.get("voice_prompt") or "风险点"
    return {
        "id": int(point["id"]),
        "device_id": ",".join(devices) if devices else "multi_device",
        "deviceId": ",".join(devices) if devices else "multi_device",
        "risk_type": point["risk_type"],
        "riskType": point["risk_type"],
        "risk_level": point["risk_level"],
        "riskLevel": point["risk_level"],
        "level": point["risk_level"],
        "lat": point["lat"],
        "lng": point["lng"],
        "latitude": point["lat"],
        "longitude": point["lng"],
        "timestamp": point["last_reported_at"],
        "message": message,
        "voice_prompt": point.get("voice_prompt") or message,
        "voicePrompt": point.get("voice_prompt") or message,
        "confidence": point.get("confidence"),
        "reportCount": point.get("report_count"),
        "report_count": point.get("report_count"),
        "sourceDevices": devices,
        "source_devices": devices,
        "expiresAt": point.get("expires_at"),
        "expires_at": point.get("expires_at"),
        "status": point.get("status"),
        "distance_m": distance_m,
        "distanceM": distance_m,
    }


def active_risk_points(lat: Optional[float] = None, lng: Optional[float] = None, radius: Optional[float] = None, limit: int = 200) -> list[dict[str, Any]]:
    expire_risk_points()
    with db() as conn:
        rows = conn.execute("SELECT * FROM risk_points WHERE status = 'active' ORDER BY last_reported_at DESC LIMIT ?", (max(limit * 5, limit),)).fetchall()
    points: list[dict[str, Any]] = []
    for row in rows:
        if is_legacy_sim_point(float(row["lat"]), float(row["lng"])):
            continue
        distance_m = None
        if lat is not None and lng is not None:
            distance_m = haversine_m(lat, lng, float(row["lat"]), float(row["lng"]))
            if radius is not None and distance_m > radius:
                continue
        point = risk_point_to_event_dict(row, lat, lng)
        points.append(point)
    points.sort(key=lambda item: (LEVEL_RANK.get(str(item.get("riskLevel") or "low"), 0), item.get("confidence") or 0, -(item.get("distanceM") or 0)), reverse=True)
    return points[:limit]


def latest_location_for_device(device_id: str) -> Optional[dict[str, Any]]:
    with db() as conn:
        row = conn.execute(
            "SELECT * FROM device_locations WHERE device_id = ? ORDER BY id DESC LIMIT 1",
            (device_id,),
        ).fetchone()
    return row_to_dict(row) if row else None


def resolve_legacy_location(device_id: str, lat: Optional[float], lng: Optional[float]) -> tuple[float, float]:
    if lat is not None and lng is not None:
        return float(lat), float(lng)
    latest = latest_location_for_device(device_id)
    if latest:
        return float(latest["lat"]), float(latest["lng"])
    return 0.0, 0.0


def latest_mobile_location_for_device(device_id: str, max_age_seconds: int = 300) -> Optional[dict[str, Any]]:
    latest = latest_location_for_device(device_id)
    if not latest:
        return None
    source = str(latest.get("source") or "").lower()
    provider = str(latest.get("provider") or "").lower()
    quality = str(latest.get("quality") or "").lower()
    if "mock" in {source, provider, quality}:
        return None
    timestamp = latest.get("timestamp")
    if timestamp:
        try:
            seen = datetime.fromisoformat(str(timestamp).replace("Z", "+00:00"))
            if seen.tzinfo is None:
                seen = seen.replace(tzinfo=timezone.utc)
            if datetime.now(timezone.utc) - seen > timedelta(seconds=max_age_seconds):
                return None
        except ValueError:
            pass
    return latest


def prefer_mobile_location(device_id: str, lat: Optional[float], lng: Optional[float]) -> tuple[float, float]:
    latest = latest_mobile_location_for_device(device_id)
    if latest:
        return float(latest["lat"]), float(latest["lng"])
    return resolve_legacy_location(device_id, lat, lng)


def legacy_device_list() -> list[dict[str, Any]]:
    devices: dict[str, dict[str, Any]] = {}
    with db() as conn:
        location_rows = conn.execute("SELECT * FROM device_locations ORDER BY id DESC").fetchall()
        event_rows = conn.execute("SELECT * FROM risk_events ORDER BY id DESC").fetchall()

    for row in location_rows:
        item = row_to_dict(row)
        device_id = item["device_id"]
        if device_id in devices:
            continue
        devices[device_id] = {
            "deviceId": device_id,
            "name": f"SmartCane {device_id}",
            "online": False,
            "battery": None,
            "lastSeen": item["timestamp"],
        }

    for row in event_rows:
        item = event_to_dict(row)
        device_id = item["device_id"]
        device = devices.setdefault(
            device_id,
            {
                "deviceId": device_id,
                "name": f"SmartCane {device_id}",
                "online": False,
                "battery": None,
                "lastSeen": item["timestamp"],
            },
        )
        if item.get("timestamp") and parse_time(item["timestamp"]) and (
            not parse_time(device.get("lastSeen")) or parse_time(item["timestamp"]) > parse_time(device.get("lastSeen"))
        ):
            device["lastSeen"] = item["timestamp"]
        battery = item.get("battery")
        if battery is not None and float(battery) >= 0:
            device["battery"] = int(float(battery))

    now = datetime.now(timezone.utc)
    for device in devices.values():
        last_seen = parse_time(device.get("lastSeen"))
        device["online"] = bool(last_seen and (now - last_seen).total_seconds() <= DEVICE_OFFLINE_SECONDS)

    return sorted(devices.values(), key=lambda item: item.get("lastSeen") or "", reverse=True)


def store_legacy_location(device_id: str, lat: Optional[float], lng: Optional[float], battery: Optional[int] = None) -> None:
    if lat is None or lng is None:
        return
    create_location(
        LocationCreate(
            device_id=device_id,
            lat=float(lat),
            lng=float(lng),
            source="app",
            provider="phone",
            quality="usable",
            accuracy_m=None,
            timestamp=now_iso(),
        )
    )


def user_display_name(user_id: str, role: str) -> str:
    known = {
        "blind_demo": "\u7528\u6237",
        "user_blind_001": "\u7528\u6237",
        "companion_demo": "\u966a\u62a4",
        "user_companion_001": "\u966a\u62a4",
    }
    if user_id in known:
        return known[user_id]
    with db() as conn:
        row = conn.execute(
            "SELECT display_name FROM users WHERE account = ? OR user_id = ?",
            (normalize_account(user_id), user_id),
        ).fetchone()
    if row:
        return str(row["display_name"])
    return ("\u7528\u6237" if role == "blind" else "\u966a\u62a4") + f" {user_id}"


def device_display_name(device_id: str) -> str:
    return "SmartCane 001" if device_id == "cane_001" else f"SmartCane {device_id}"


def is_expired(iso_value: str) -> bool:
    parsed = parse_time(iso_value)
    return bool(parsed and parsed <= datetime.now(timezone.utc))


def pairing_user(user_id: str, display_name: str) -> dict[str, Any]:
    return {"userId": user_id, "displayName": display_name}


def pairing_device(device_id: str, display_name: str) -> dict[str, Any]:
    return {"deviceId": device_id, "name": display_name}


def pairing_code_payload(row: sqlite3.Row, success: bool = True, error: Optional[str] = None) -> dict[str, Any]:
    payload = {
        "success": success,
        "code": row["code"],
        "expiresAt": row["expires_at"],
        "blindUser": pairing_user(row["blind_user_id"], row["blind_name"]),
        "device": pairing_device(row["device_id"], row["device_name"]),
    }
    if error:
        payload["error"] = error
    return payload


def care_request_payload(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "requestId": row["request_id"],
        "status": row["status"],
        "code": row["code"],
        "blindUser": pairing_user(row["blind_user_id"], row["blind_name"]),
        "companionUser": pairing_user(row["companion_user_id"], row["companion_name"]),
        "device": pairing_device(row["device_id"], row["device_name"]),
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


def care_relation_payload(row: sqlite3.Row) -> dict[str, Any]:
    return {
        "relationId": row["relation_id"],
        "status": row["status"],
        "blindUser": pairing_user(row["blind_user_id"], row["blind_name"]),
        "companionUser": pairing_user(row["companion_user_id"], row["companion_name"]),
        "device": pairing_device(row["device_id"], row["device_name"]),
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
    }


def fetch_pairing_code(code: str) -> sqlite3.Row:
    with db() as conn:
        row = conn.execute("SELECT * FROM pairing_codes WHERE code = ?", (code,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="\u672a\u627e\u5230\u8be5\u914d\u5bf9\u7801")
        if row["status"] != "active" or is_expired(row["expires_at"]):
            conn.execute("UPDATE pairing_codes SET status = 'expired' WHERE code = ?", (code,))
            raise HTTPException(status_code=410, detail="\u914d\u5bf9\u7801\u5df2\u8fc7\u671f")
        return row


def latest_relation_for_request(row: sqlite3.Row) -> Optional[dict[str, Any]]:
    with db() as conn:
        relation = conn.execute(
            """
            SELECT * FROM care_relations
            WHERE blind_user_id = ? AND companion_user_id = ? AND device_id = ? AND status = 'active'
            ORDER BY updated_at DESC
            LIMIT 1
            """,
            (row["blind_user_id"], row["companion_user_id"], row["device_id"]),
        ).fetchone()
    return care_relation_payload(relation) if relation else None


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def risk_level_from_score(score: float) -> str:
    if score >= 70:
        return "high"
    if score >= 40:
        return "medium"
    return "low"


def risk_level_for_analysis(risk_type: str, score: float) -> str:
    if risk_type in {"sos", "fall_detected"}:
        return "high"
    if risk_type in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable", "user_mark"}:
        return "medium" if score > 0 else "low"
    if risk_type in {"front_obstacle", "left_obstacle", "right_obstacle"}:
        return risk_level_from_score(score)
    if risk_type in {
        "down_obstacle",
        "history_risk",
        "prolonged_obstacle",
        "approaching_obstacle",
        "voice_request",
    }:
        return "low"
    return risk_level_from_score(score)


def primary_distance_mm(risk_type: str, frame: SensorFrameCreate) -> Optional[int]:
    if risk_type == "front_obstacle" and frame.front_cm is not None:
        return frame.front_cm * 10
    if risk_type == "left_obstacle" and frame.left_cm is not None:
        return frame.left_cm * 10
    if risk_type == "right_obstacle" and frame.right_cm is not None:
        return frame.right_cm * 10
    if risk_type in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable", "down_obstacle", "fall_detected"} and frame.down_cm is not None:
        return frame.down_cm * 10
    if risk_type in {"prolonged_obstacle", "approaching_obstacle"} and frame.front_cm is not None:
        return frame.front_cm * 10
    return None


def front_score(front_cm: Optional[int]) -> float:
    if front_cm is None:
        return 0.0
    if front_cm <= FRONT_DANGER_CM:
        return clamp(72 + (FRONT_DANGER_CM - front_cm) * 0.35, 72, 90)
    if front_cm < FRONT_WARN_CM:
        return clamp(10 + (FRONT_WARN_CM - front_cm) * 0.24, 10, 32)
    return 0.0


def side_score(side_cm: Optional[int]) -> float:
    if side_cm is None:
        return 0.0
    if side_cm <= SIDE_DANGER_CM:
        return clamp(72 + (SIDE_DANGER_CM - side_cm) * 0.70, 72, 90)
    if side_cm < SIDE_NEAR_CM:
        return clamp(12 + (SIDE_NEAR_CM - side_cm) * 1.70, 12, 24)
    return 0.0


def ground_score(down_cm: Optional[int]) -> float:
    return 0.0


def down_obstacle_score(down_cm: Optional[int]) -> float:
    if down_cm is None:
        return 0.0
    if down_cm < DOWN_OBSTACLE_CM:
        return clamp(12 + (DOWN_OBSTACLE_CM - down_cm) * 0.8, 12, 28)
    return 0.0


def ground_step_score(down_cm: Optional[int]) -> float:
    if down_cm is None:
        return 0.0
    if down_cm > DOWN_DROP_CM:
        return clamp(55 + (down_cm - DOWN_DROP_CM) * 0.25, 55, 75)
    return 0.0


def history_score(history: dict[str, Any]) -> float:
    return clamp(
        history.get("high_count", 0) * 2
        + history.get("medium_count", 0) * 1.5
        + history.get("risk_count", 0) * 0.2,
        0,
        10,
    )


def choose_direction(frame: SensorFrameCreate, risk_type: str, level: str) -> str:
    if risk_type == "voice_request":
        return "none"
    if risk_type in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "fall_detected", "sos", "down_no_target", "down_sensor_unavailable"}:
        return "stop"
    if risk_type == "prolonged_obstacle":
        return "stop"
    if risk_type == "approaching_obstacle":
        return "slow"
    if risk_type == "down_obstacle":
        return "slow"
    if risk_type == "left_obstacle":
        return "keep_right"
    if risk_type == "right_obstacle":
        return "keep_left"
    if risk_type == "history_risk":
        return "slow"
    if risk_type == "front_obstacle":
        left = frame.left_cm if frame.left_cm is not None else 0
        right = frame.right_cm if frame.right_cm is not None else 0
        if left < SIDE_SAFE_CM and right < SIDE_SAFE_CM:
            return "stop"
        if left > right and left >= SIDE_SAFE_CM:
            return "turn_left"
        if right > left and right >= SIDE_SAFE_CM:
            return "turn_right"
        return "stop" if level == "high" else "slow"
    return "none"


def imu_fall_score(frame: SensorFrameCreate) -> float:
    if frame.fall_detected is True or frame.manual_risk_type == "fall_detected":
        return 100.0
    if frame.fall_confidence is not None and frame.fall_confidence >= 0.80:
        return 92.0
    if frame.fall_stage in {"confirmed", "mock_confirmed"}:
        return 92.0
    if frame.accel_total_g is None:
        return 0.0
    if frame.accel_total_g >= 2.6:
        return 72.0
    if frame.accel_total_g <= 0.30:
        return 58.0
    return 0.0


def feedback_for_risk(risk_type: str, level: str, direction: str) -> dict[str, Any]:
    if risk_type == "voice_request":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 0, "right": 0, "center": 0, "duration_ms": 0},
            "action": "start_voice_input",
        }
    if risk_type == "sos":
        return {
            "buzzer": {"enabled": True, "beeps": 5, "pattern": "sos"},
            "vibration": {"left": 100, "right": 100, "center": 100, "duration_ms": 1200},
            "action": "emergency_contact",
        }
    if risk_type == "fall_detected":
        return {
            "buzzer": {"enabled": True, "beeps": 4, "pattern": "fall_or_urgent"},
            "vibration": {"left": 0, "right": 0, "center": 0, "duration_ms": 0},
            "action": "stop_and_confirm",
        }
    if risk_type == "prolonged_obstacle":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 0, "right": 0, "center": 45, "duration_ms": 300},
            "action": "notify_companion",
        }
    if risk_type == "approaching_obstacle":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 0, "right": 0, "center": 45, "duration_ms": 350},
            "action": "slow",
        }
    if risk_type in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable"}:
        return {
            "buzzer": {"enabled": True, "beeps": 2, "pattern": risk_type},
            "vibration": {"left": 70, "right": 70, "center": 85, "duration_ms": 650},
            "action": "stop",
        }
    if risk_type == "down_obstacle":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 30, "right": 30, "center": 45, "duration_ms": 300},
            "action": "slow",
        }
    if risk_type in {"front_obstacle", "left_obstacle", "right_obstacle"}:
        center_level = 45 if level == "low" else (70 if level == "medium" else 100)
        side_level = 45 if level == "low" else 80
        motors = {"left": 0, "right": 0, "center": center_level}
        if direction in {"turn_left", "keep_left"}:
            motors["left"] = side_level
        elif direction in {"turn_right", "keep_right"}:
            motors["right"] = side_level
        elif direction == "stop":
            motors = {"left": side_level, "right": side_level, "center": center_level}
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {**motors, "duration_ms": 280 if level == "low" else 350},
            "action": direction,
        }
    if risk_type == "history_risk":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 0, "right": 0, "center": 35, "duration_ms": 250},
            "action": "slow",
        }
    if risk_type == "user_mark":
        return {
            "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
            "vibration": {"left": 35, "right": 35, "center": 35, "duration_ms": 250},
            "action": "recorded",
        }
    return {
        "buzzer": {"enabled": False, "beeps": 0, "pattern": "none"},
        "vibration": {"left": 0, "right": 0, "center": 0, "duration_ms": 0},
        "action": "continue",
    }


def risk_reason_for_risk(frame: SensorFrameCreate, risk_type: str, level: str, score: float) -> str:
    if risk_type == "ground_step_down":
        return f"down baseline delta >= 20cm, current down distance {frame.down_cm or '-'}cm"
    if risk_type == "ground_step_up":
        return f"down baseline delta <= -20cm, current down distance {frame.down_cm or '-'}cm"
    if risk_type == "ground_step":
        return f"down distance {frame.down_cm or '-'}cm legacy ground_step"
    if risk_type == "none" or score <= 0:
        return "四路测距和 IMU 未发现需要提醒的风险"
    if risk_type == "voice_request":
        return "盲杖按钮短按，请求手机端语音输入"
    if risk_type == "sos":
        return "盲杖 SOS 长按触发"
    if risk_type == "fall_detected":
        return f"BMI270 检测到疑似跌倒，阶段={frame.fall_stage or '-'}，置信度={frame.fall_confidence if frame.fall_confidence is not None else '-'}"
    if risk_type == "prolonged_obstacle":
        return "同一方向障碍持续存在，超过陪护端通知阈值"
    if risk_type == "approaching_obstacle":
        return "前方距离在一段时间内持续缩短，存在逼近风险"
    if risk_type == "ground_drop":
        return f"下视距离 {frame.down_cm or '-'}cm 超过地面基准 {GROUND_BASE_CM}cm + 落差阈值 {GROUND_DROP_THRESHOLD_CM}cm"
    if risk_type == "down_obstacle":
        return f"下视距离 {frame.down_cm or '-'}cm 小于近地障碍阈值 {DOWN_OBSTACLE_CM}cm"
    if risk_type == "front_obstacle":
        return f"前方距离 {frame.front_cm or '-'}cm 小于斜持盲杖前向阈值 {FRONT_WARN_CM}/{FRONT_DANGER_CM}cm"
    if risk_type == "left_obstacle":
        return f"左侧距离 {frame.left_cm or '-'}cm 小于侧向扫杖阈值 {SIDE_NEAR_CM}/{SIDE_DANGER_CM}cm"
    if risk_type == "right_obstacle":
        return f"右侧距离 {frame.right_cm or '-'}cm 小于侧向扫杖阈值 {SIDE_NEAR_CM}/{SIDE_DANGER_CM}cm"
    if risk_type == "history_risk":
        return "当前位置附近存在多人历史高风险记录"
    if risk_type == "user_mark":
        return "用户通过触控/按钮主动标记风险点"
    return f"{risk_type} risk score={score:.1f} level={level}"


def map_weight_for_risk(risk_type: str, level: str, score: float) -> float:
    if risk_type in {"fall_detected", "sos"}:
        return 100.0
    if risk_type in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable"}:
        return clamp(score, 55, 75)
    if risk_type == "user_mark":
        return 60.0
    if risk_type == "prolonged_obstacle":
        return 24.0
    if risk_type == "approaching_obstacle":
        return 22.0
    if risk_type == "down_obstacle":
        return 12.0
    if risk_type in {"front_obstacle", "left_obstacle", "right_obstacle"}:
        # Only confirmed medium/high obstacle events become shared map points.
        # Low-level sweep noise remains local vibration-only.
        if level == "high":
            return clamp(score, 70, 90)
        if level == "medium":
            return clamp(score, 60, 75)
        return 8.0
    return 0.0



STEP_BASELINE_STABLE_FRAMES = 5
STEP_BASELINE_TOLERANCE_CM = 3
STEP_CONFIRM_FRAMES = 2
STEP_DELTA_CM = 20
STEP_CLEAR_HYSTERESIS_CM = 12
STEP_POSE_DELTA_DEG = 12.0
STEP_SWING_G_DELTA = 0.45


class DownStepTracker:
    """Per-device stable-ground step detector.

    The backend mirrors the firmware state machine so periodic frames are not
    reinterpreted with a fixed >90cm threshold.  It learns a stable down-facing
    baseline, confirms 20cm front/back ground deltas over consecutive frames,
    freezes the baseline while the user crosses the edge, then relearns on the
    new stable ground.
    """

    def __init__(self) -> None:
        self.state = "NORMAL_GROUND"
        self.baseline: Optional[float] = None
        self.samples: list[float] = []
        self.candidate_direction: Optional[str] = None
        self.candidate_frames = 0
        self.confirmed_direction: Optional[str] = None
        self.last_pitch: Optional[float] = None
        self.last_roll: Optional[float] = None
        self.last_g: Optional[float] = None
        self.fail_count = 0
        self.stable_frames = 0

    def pose_stable(self, frame: SensorFrameCreate) -> bool:
        if frame.pitch_deg is None and frame.roll_deg is None and frame.accel_total_g is None:
            return True
        stable = True
        if self.last_pitch is not None and frame.pitch_deg is not None:
            stable = stable and abs(frame.pitch_deg - self.last_pitch) <= STEP_POSE_DELTA_DEG
        if self.last_roll is not None and frame.roll_deg is not None:
            stable = stable and abs(frame.roll_deg - self.last_roll) <= STEP_POSE_DELTA_DEG
        if self.last_g is not None and frame.accel_total_g is not None:
            stable = stable and abs(frame.accel_total_g - self.last_g) <= STEP_SWING_G_DELTA
        if frame.pitch_deg is not None:
            self.last_pitch = frame.pitch_deg
        if frame.roll_deg is not None:
            self.last_roll = frame.roll_deg
        if frame.accel_total_g is not None:
            self.last_g = frame.accel_total_g
        return stable

    def update_baseline(self, down_cm: float) -> None:
        self.samples.append(down_cm)
        if len(self.samples) > STEP_BASELINE_STABLE_FRAMES:
            self.samples.pop(0)
        if len(self.samples) >= STEP_BASELINE_STABLE_FRAMES and max(self.samples) - min(self.samples) <= STEP_BASELINE_TOLERANCE_CM:
            self.baseline = sum(self.samples) / len(self.samples)
            self.stable_frames = min(255, self.stable_frames + 1)
        else:
            self.stable_frames = 0

    def process(self, frame: SensorFrameCreate) -> dict[str, Any]:
        status = (frame.down_status or "ok").lower()
        down_value = frame.down_raw_cm if frame.down_raw_cm is not None else frame.down_cm
        # VL53L1X sentinel: 400 means no valid target. It is diagnostic data,
        # never a drop/step alarm.
        if down_value == 400 or status in {"no_target", "out_of_range"}:
            self.candidate_frames = 0
            return {"risk_type": "none", "score": 0.0, "reason": "400cm/no-target sentinel ignored", "confidence": 0.0}
        if frame.down_valid is False or status in {"timeout", "offline", "disconnected", "unavailable", "invalid"}:
            self.fail_count = min(255, self.fail_count + 1)
            self.candidate_frames = 0
            return {"risk_type": "down_sensor_unavailable", "score": 58.0, "reason": "down sensor unavailable", "confidence": 0.70}
        if down_value is None:
            return {"risk_type": "none", "score": 0.0, "reason": "down distance absent", "confidence": 0.0}
        down_cm = float(down_value)
        self.fail_count = 0
        pose_ok = self.pose_stable(frame)
        if self.baseline is None:
            if pose_ok:
                self.update_baseline(down_cm)
            return {"risk_type": "none", "score": 0.0, "reason": "learning stable down baseline", "confidence": 0.25}
        delta = down_cm - self.baseline
        if self.state == "NORMAL_GROUND":
            if pose_ok and abs(delta) < STEP_DELTA_CM:
                self.update_baseline(down_cm)
                return {"risk_type": "none", "score": 0.0, "reason": f"baseline={self.baseline:.1f}cm delta={delta:.1f}cm", "confidence": 0.45}
            if not pose_ok:
                return {"risk_type": "none", "score": 0.0, "reason": "pose changing; baseline frozen", "confidence": 0.30}
            direction = "down" if (delta >= STEP_DELTA_CM or down_cm > 150) else None
            if direction:
                self.state = "STEP_CANDIDATE_DOWN" if direction == "down" else "STEP_CANDIDATE_UP"
                self.candidate_direction = direction
                self.candidate_frames = 1
            return {"risk_type": "none", "score": 0.0, "reason": f"step candidate delta={delta:.1f}cm", "confidence": 0.45}
        if self.state.startswith("STEP_CANDIDATE"):
            expected = self.candidate_direction
            direction = "down" if (delta >= STEP_DELTA_CM or down_cm > 150) else None
            if direction == expected and pose_ok:
                self.candidate_frames += 1
            else:
                self.state = "NORMAL_GROUND"
                self.candidate_frames = 0
                self.candidate_direction = None
                return {"risk_type": "none", "score": 0.0, "reason": "single-frame step delta rejected", "confidence": 0.35}
            if self.candidate_frames >= STEP_CONFIRM_FRAMES:
                self.state = "STEP_CONFIRMED"
                self.confirmed_direction = expected
                rtype = "ground_step_down" if expected == "down" else "ground_step_up"
                prompt = "front/back ground delta confirmed"
                return {"risk_type": rtype, "score": 62.0, "reason": f"{prompt}: baseline={self.baseline:.1f}cm current={down_cm:.1f}cm delta={delta:.1f}cm", "confidence": 0.86}
            return {"risk_type": "none", "score": 0.0, "reason": "waiting consecutive step frames", "confidence": 0.45}
        if self.state == "STEP_CONFIRMED":
            self.state = "WAIT_NEW_GROUND"
            rtype = "ground_step_down" if self.confirmed_direction == "down" else "ground_step_up"
            return {"risk_type": rtype, "score": 62.0, "reason": "step confirmed; baseline frozen", "confidence": 0.82}
        if self.state == "WAIT_NEW_GROUND":
            if abs(delta) <= max(1, STEP_DELTA_CM - STEP_CLEAR_HYSTERESIS_CM):
                self.state = "NORMAL_GROUND"
                self.samples.clear()
                if pose_ok:
                    self.update_baseline(down_cm)
                self.confirmed_direction = None
                return {"risk_type": "none", "score": 0.0, "reason": "step cleared by hysteresis", "confidence": 0.45}
            # Relearn a new baseline only after the new ground is stable.
            if pose_ok:
                self.samples.append(down_cm)
                if len(self.samples) > STEP_BASELINE_STABLE_FRAMES:
                    self.samples.pop(0)
                if len(self.samples) >= STEP_BASELINE_STABLE_FRAMES and max(self.samples) - min(self.samples) <= STEP_BASELINE_TOLERANCE_CM:
                    self.baseline = sum(self.samples) / len(self.samples)
                    self.state = "NORMAL_GROUND"
                    self.confirmed_direction = None
                    self.candidate_frames = 0
                    return {"risk_type": "none", "score": 0.0, "reason": "new ground baseline learned", "confidence": 0.45}
            rtype = "ground_step_down" if self.confirmed_direction == "down" else "ground_step_up"
            return {"risk_type": rtype, "score": 55.0, "reason": "waiting for new stable ground", "confidence": 0.70}
        self.state = "NORMAL_GROUND"
        return {"risk_type": "none", "score": 0.0, "reason": "state reset", "confidence": 0.2}


_DOWN_STEP_TRACKERS: dict[str, DownStepTracker] = {}
_FALL_EVENT_IDS_SEEN: set[str] = set()


def reset_runtime_detectors() -> None:
    _DOWN_STEP_TRACKERS.clear()
    _FALL_EVENT_IDS_SEEN.clear()


def analyze_down_step_frame(frame: SensorFrameCreate) -> dict[str, Any]:
    tracker = _DOWN_STEP_TRACKERS.setdefault(frame.device_id, DownStepTracker())
    return tracker.process(frame)

def analyze_sensor_frame(frame: SensorFrameCreate, history: dict[str, Any]) -> dict[str, Any]:
    explicit_risk = frame.risk_type or frame.manual_risk_type or frame.alert_type
    if frame.fall_pending and not frame.fall_detected:
        risk_type = "none"
        score = 0.0
        fall_pending_suppressed = True
    else:
        fall_pending_suppressed = False
        if frame.fall_event_id and frame.fall_event_id in _FALL_EVENT_IDS_SEEN:
            return {
                "risk_type": "none",
                "risk_level": "low",
                "risk_score": 0.0,
                "map_weight": 0.0,
                "mapWeight": 0.0,
                "risk_reason": "duplicate fall_event_id ignored",
                "riskReason": "duplicate fall_event_id ignored",
                "risk_source_detail": "duplicate fall_event_id ignored",
                "riskSourceDetail": "duplicate fall_event_id ignored",
                "direction": "none",
                "sensor": "none",
                "distance_mm": None,
                "voice_prompt": "",
                "feedback": feedback_for_risk("none", "low", "none"),
                "nearby_history": {"risk_count": history.get("risk_count", 0), "high_count": history.get("high_count", 0), "medium_count": history.get("medium_count", 0), "max_level": history.get("max_level", "low")},
            }
        if frame.button_event == "short_press" or explicit_risk == "voice_request":
            risk_type = "voice_request"
            score = 1.0
        elif frame.button_event in {"long_press", "sos"} or explicit_risk == "sos":
            risk_type = "sos"
            score = 100.0
        elif frame.fall_detected is True or explicit_risk == "fall_detected" or imu_fall_score(frame) >= 90:
            risk_type = "fall_detected"
            score = 100.0 if frame.fall_detected is True else imu_fall_score(frame)
            if frame.fall_event_id:
                _FALL_EVENT_IDS_SEEN.add(frame.fall_event_id)
        elif explicit_risk in {"prolonged_obstacle", "approaching_obstacle"}:
            risk_type = str(explicit_risk)
            score = 34.0 if risk_type == "prolonged_obstacle" else 30.0
        elif explicit_risk in {"ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable"}:
            risk_type = str(explicit_risk)
            score = 58.0
        elif explicit_risk in {"ground_drop", "ground_step"}:
            # Compatibility for older firmware: use the new baseline detector if no direction is supplied.
            step = analyze_down_step_frame(frame)
            risk_type = step["risk_type"] if step["risk_type"] != "none" else str(explicit_risk)
            score = float(step.get("score") or 58.0)
        elif explicit_risk == "down_obstacle":
            # Deprecated: the down-facing sensor is reserved for ground
            # discontinuity detection, not near-object obstacle alarms.
            risk_type = "none"
            score = 0.0
        elif frame.touch_electrode == 1 and frame.touch_event == "long_press":
            risk_type = "user_mark"
            score = max(45.0, history_score(history))
        else:
            step = analyze_down_step_frame(frame)
            scores = {
                "front_obstacle": front_score(frame.front_cm),
                "left_obstacle": side_score(frame.left_cm),
                "right_obstacle": side_score(frame.right_cm),
                step["risk_type"]: float(step.get("score") or 0.0),
            }
            scores.pop("none", None)
            risk_type, score = max(scores.items(), key=lambda item: item[1]) if scores else ("none", 0.0)
            hist_score = history_score(history)
            if score > 0 and risk_type not in {"ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable"}:
                score = max(score, min(100.0, score + hist_score * 0.12))

    public_risk_type = risk_type if score > 0 else "none"
    level = risk_level_for_analysis(public_risk_type, score)
    direction = frame.direction or choose_direction(frame, public_risk_type, level)
    feedback = feedback_for_risk(public_risk_type, level, direction)
    reason = frame.reason or risk_reason_for_risk(frame, public_risk_type, level, score)
    if fall_pending_suppressed:
        reason = "slow fall is in cancel window; formal fall alert suppressed"
    map_weight = round(map_weight_for_risk(public_risk_type, level, score), 1)
    confidence = frame.confidence
    if confidence is None:
        confidence = 0.86 if public_risk_type in {"ground_step_down", "ground_step_up"} else (0.92 if public_risk_type == "fall_detected" else min(0.95, max(0.15, score / 100.0)))
    return {
        "risk_type": public_risk_type,
        "risk_level": level,
        "risk_score": round(score, 1),
        "map_weight": map_weight,
        "mapWeight": map_weight,
        "risk_reason": reason,
        "riskReason": reason,
        "risk_source_detail": reason,
        "riskSourceDetail": reason,
        "direction": direction,
        "confidence": round(float(confidence), 3),
        "sensor": {
            "front_obstacle": "tof_front",
            "left_obstacle": "tof_left",
            "right_obstacle": "tof_right",
            "ground_drop": "tof_down",
            "ground_step": "tof_down",
            "ground_step_down": "tof_down",
            "ground_step_up": "tof_down",
            "down_no_target": "tof_down",
            "down_sensor_unavailable": "tof_down",
            "down_obstacle": "tof_down",
            "user_mark": "touch",
            "sos": "sos_button",
            "fall_detected": "bmi270_imu",
            "voice_request": "button_voice",
            "prolonged_obstacle": "tof_trend",
            "approaching_obstacle": "tof_trend",
            "history_risk": "backend_history",
        }.get(public_risk_type, "none"),
        "distance_mm": primary_distance_mm(public_risk_type, frame),
        "voice_prompt": voice_prompt_for_risk(frame, public_risk_type, level, direction),
        "feedback": feedback,
        "fall_event_id": frame.fall_event_id,
        "fall_pending": bool(frame.fall_pending),
        "fall_detected": bool(frame.fall_detected),
        "fall_stage": frame.fall_stage,
        "fall_confidence": frame.fall_confidence,
        "nearby_history": {
            "risk_count": history.get("risk_count", 0),
            "high_count": history.get("high_count", 0),
            "medium_count": history.get("medium_count", 0),
            "max_level": history.get("max_level", "low"),
        },
    }

def should_store_sensor_analysis(analysis: dict[str, Any]) -> bool:
    if analysis["risk_type"] in {"voice_request", "user_mark", "sos", "fall_detected"}:
        return True
    if analysis["risk_type"] in {"ground_drop", "ground_step", "ground_step_down", "ground_step_up", "down_no_target", "down_sensor_unavailable"}:
        return float(analysis.get("map_weight") or 0) >= 55
    return analysis["risk_type"] in {
        "front_obstacle",
        "left_obstacle",
        "right_obstacle",
        "prolonged_obstacle",
        "approaching_obstacle",
    } and float(analysis.get("map_weight") or 0) >= 60 and analysis["risk_level"] in {"medium", "high"}


def amap_key() -> str:
    return env("AMAP_WEB_KEY") or env("GAODE_WEB_KEY") or env("AMAP_KEY")


def amap_configured() -> bool:
    return bool(amap_key())


def amap_location(lng: float, lat: float) -> str:
    return f"{lng:.6f},{lat:.6f}"


def parse_amap_location(value: Any) -> Optional[tuple[float, float]]:
    text = str(value or "")
    if "," not in text:
        return None
    try:
        lng_text, lat_text = text.split(",", 1)
        return float(lat_text), float(lng_text)
    except ValueError:
        return None


async def amap_get(path: str, params: dict[str, Any]) -> dict[str, Any]:
    key = amap_key()
    if not key:
        raise HTTPException(status_code=503, detail="AMAP_WEB_KEY is not configured")
    payload = {**params, "key": key, "output": "JSON"}
    async with httpx.AsyncClient(timeout=12.0, trust_env=False) as client:
        response = await client.get(f"{AMAP_BASE_URL}{path}", params=payload)
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)
    data = response.json()
    if str(data.get("status")) != "1":
        raise HTTPException(
            status_code=502,
            detail={"message": "Amap API error", "infocode": data.get("infocode"), "info": data.get("info")},
        )
    return data


async def convert_to_amap_coord(lat: float, lng: float, coordsys: str = "gps") -> tuple[float, float]:
    normalized = coordsys.lower()
    if normalized in {"amap", "gcj02", "gcj-02", "autonavi", "gaode"}:
        return lat, lng
    amap_coordsys = "gps" if normalized in {"gps", "wgs84", "wgs-84", "gnss", "beidou"} else normalized
    data = await amap_get(
        "/assistant/coordinate/convert",
        {"locations": amap_location(lng, lat), "coordsys": amap_coordsys},
    )
    converted = str(data.get("locations", "")).split(";")[0]
    converted_lng, converted_lat = [float(part) for part in converted.split(",", 1)]
    return converted_lat, converted_lng


async def geocode_address(address: str, city: Optional[str] = None) -> dict[str, Any]:
    params: dict[str, Any] = {"address": address}
    if city:
        params["city"] = city
    try:
        data = await amap_get("/geocode/geo", params)
    except HTTPException:
        if not city:
            raise
        data = await amap_get("/geocode/geo", {"address": address})
    geocodes = data.get("geocodes") or []
    if not geocodes:
        raise HTTPException(status_code=404, detail=f"Amap geocode not found: {address}")
    item = geocodes[0]
    lng, lat = [float(part) for part in str(item["location"]).split(",", 1)]
    return {
        "address": address,
        "formatted_address": item.get("formatted_address", address),
        "province": item.get("province"),
        "city": item.get("city"),
        "district": item.get("district"),
        "lat": lat,
        "lng": lng,
        "coordsys": "amap",
        "raw": item,
    }


async def search_pois_around(
    keyword: str,
    origin_lat: float,
    origin_lng: float,
    origin_coordsys: str,
    city: Optional[str] = None,
    radius_m: float = WALKING_NAVIGATION_MAX_DISTANCE_M,
) -> list[dict[str, Any]]:
    origin_amap_lat, origin_amap_lng = await convert_to_amap_coord(origin_lat, origin_lng, origin_coordsys)
    params: dict[str, Any] = {
        "location": amap_location(origin_amap_lng, origin_amap_lat),
        "keywords": keyword,
        "radius": int(radius_m),
        "offset": 10,
        "page": 1,
        "extensions": "base",
        "sortrule": "distance",
    }
    if city:
        params["city"] = city
        params["citylimit"] = "true"
    data = await amap_get("/place/around", params)
    candidates: list[dict[str, Any]] = []
    for poi in data.get("pois") or []:
        location = parse_amap_location(poi.get("location"))
        if not location:
            continue
        lat, lng = location
        try:
            distance_m = float(poi.get("distance") or haversine_m(origin_amap_lat, origin_amap_lng, lat, lng))
        except (TypeError, ValueError):
            distance_m = haversine_m(origin_amap_lat, origin_amap_lng, lat, lng)
        candidates.append(
            {
                "id": poi.get("id"),
                "name": poi.get("name") or keyword,
                "address": poi.get("address") or "",
                "province": poi.get("pname"),
                "city": poi.get("cityname"),
                "district": poi.get("adname"),
                "lat": lat,
                "lng": lng,
                "coordsys": "amap",
                "distance_m": round(distance_m, 1),
                "raw": poi,
            }
        )
    candidates.sort(key=lambda item: item["distance_m"])
    return candidates


async def resolve_destination_address(
    address: str,
    city: Optional[str],
    origin_lat: Optional[float],
    origin_lng: Optional[float],
    origin_coordsys: str,
) -> dict[str, Any]:
    nearby_candidates: list[dict[str, Any]] = []
    if origin_lat is not None and origin_lng is not None:
        try:
            nearby_candidates = await search_pois_around(address, origin_lat, origin_lng, origin_coordsys, city)
        except HTTPException:
            nearby_candidates = []
        if nearby_candidates:
            best = nearby_candidates[0]
            return {
                "address": address,
                "formatted_address": best["name"],
                "province": best.get("province"),
                "city": best.get("city"),
                "district": best.get("district"),
                "lat": best["lat"],
                "lng": best["lng"],
                "coordsys": "amap",
                "source": "amap_place_around",
                "distance_m": best["distance_m"],
                "candidates": nearby_candidates[:5],
                "raw": best.get("raw"),
            }

    geocoded = await geocode_address(address, city)
    geocoded["source"] = "amap_geocode"
    if nearby_candidates:
        geocoded["candidates"] = nearby_candidates[:5]
    return geocoded


async def reverse_geocode(lat: float, lng: float, coordsys: str = "gps") -> dict[str, Any]:
    amap_lat, amap_lng = await convert_to_amap_coord(lat, lng, coordsys)
    data = await amap_get(
        "/geocode/regeo",
        {
            "location": amap_location(amap_lng, amap_lat),
            "extensions": "base",
            "radius": 100,
            "roadlevel": 0,
        },
    )
    regeocode = data.get("regeocode") or {}
    return {
        "input": {"lat": lat, "lng": lng, "coordsys": coordsys},
        "amap_location": {"lat": amap_lat, "lng": amap_lng},
        "formatted_address": regeocode.get("formatted_address", ""),
        "address_component": regeocode.get("addressComponent", {}),
    }


def parse_polyline(polyline: str) -> list[dict[str, float]]:
    points: list[dict[str, float]] = []
    for token in polyline.split(";"):
        if not token or "," not in token:
            continue
        lng_text, lat_text = token.split(",", 1)
        try:
            points.append({"lat": float(lat_text), "lng": float(lng_text)})
        except ValueError:
            continue
    return points


def route_points_from_path(path: dict[str, Any]) -> list[dict[str, float]]:
    points: list[dict[str, float]] = []
    for step in path.get("steps", []):
        points.extend(parse_polyline(str(step.get("polyline", ""))))
    deduped: list[dict[str, float]] = []
    last: Optional[dict[str, float]] = None
    for point in points:
        if last is None or haversine_m(point["lat"], point["lng"], last["lat"], last["lng"]) > 2:
            deduped.append(point)
            last = point
    return deduped


def min_distance_to_points(lat: float, lng: float, points: list[dict[str, float]]) -> float:
    if not points:
        return float("inf")
    return min(haversine_m(lat, lng, point["lat"], point["lng"]) for point in points)


def local_xy_m(lat: float, lng: float, ref_lat: float, ref_lng: float) -> tuple[float, float]:
    radius_m = 6371000.0
    x = math.radians(lng - ref_lng) * radius_m * math.cos(math.radians(ref_lat))
    y = math.radians(lat - ref_lat) * radius_m
    return x, y


def point_to_segment_distance_m(
    point_lat: float,
    point_lng: float,
    start_lat: float,
    start_lng: float,
    end_lat: float,
    end_lng: float,
) -> float:
    px, py = local_xy_m(point_lat, point_lng, start_lat, start_lng)
    ax, ay = 0.0, 0.0
    bx, by = local_xy_m(end_lat, end_lng, start_lat, start_lng)
    dx = bx - ax
    dy = by - ay
    length_sq = dx * dx + dy * dy
    if length_sq <= 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length_sq))
    closest_x = ax + t * dx
    closest_y = ay + t * dy
    return math.hypot(px - closest_x, py - closest_y)


def min_distance_to_polyline_m(lat: float, lng: float, points: list[dict[str, float]]) -> float:
    if not points:
        return float("inf")
    if len(points) == 1:
        return haversine_m(lat, lng, points[0]["lat"], points[0]["lng"])
    return min(
        point_to_segment_distance_m(
            lat,
            lng,
            points[index]["lat"],
            points[index]["lng"],
            points[index + 1]["lat"],
            points[index + 1]["lng"],
        )
        for index in range(len(points) - 1)
    )


ROAD_FIXED_RISK_TYPES = {"ground_step_down", "ground_step_up", "ground_drop", "ground_step", "confirmed_user_mark", "fixed_obstacle"}
ROAD_TEMPORARY_RISK_TYPES = {"temporary_obstacle", "construction", "approaching_obstacle"}
ROAD_EXCLUDED_RISK_TYPES = {"fall_detected", "sos", "voice_request", "fall_cancelled", "down_sensor_unavailable", "down_no_target"}


def road_segment_length(points: list[dict[str, float]]) -> float:
    if len(points) < 2:
        return 0.0
    return sum(haversine_m(points[i]["lat"], points[i]["lng"], points[i + 1]["lat"], points[i + 1]["lng"]) for i in range(len(points) - 1))


def upsert_road_segment_from_step(step: dict[str, Any], city: Optional[str] = None, district: Optional[str] = None) -> Optional[int]:
    points = parse_polyline(str(step.get("polyline") or ""))
    if len(points) < 2:
        return None
    road_name = str(step.get("road") or "").strip()
    if not road_name:
        return None
    start = points[0]
    end = points[-1]
    length_m = float(step.get("distance") or 0) or road_segment_length(points)
    segment_key = hashlib.sha256(
        f"{road_name}|{start['lat']:.5f}|{start['lng']:.5f}|{end['lat']:.5f}|{end['lng']:.5f}".encode("utf-8")
    ).hexdigest()[:24]
    now = now_iso()
    with db() as conn:
        row = conn.execute(
            """
            SELECT id FROM road_segments
            WHERE road_segment_key = ? OR (
              road_name = ? AND ABS(start_lat - ?) < 0.00008 AND ABS(start_lng - ?) < 0.00008
              AND ABS(end_lat - ?) < 0.00008 AND ABS(end_lng - ?) < 0.00008)
            LIMIT 1
            """,
            (segment_key, road_name, start["lat"], start["lng"], end["lat"], end["lng"]),
        ).fetchone()
        if row:
            segment_id = int(row["id"])
            conn.execute(
                "UPDATE road_segments SET road_segment_key = ?, polyline_json = ?, length_m = ?, city = COALESCE(?, city), district = COALESCE(?, district), updated_at = ? WHERE id = ?",
                (segment_key, json.dumps(points), length_m, city, district, now, segment_id),
            )
            return segment_id
        cur = conn.execute(
            """
            INSERT INTO road_segments (road_name, road_segment_key, start_lat, start_lng, end_lat, end_lng, polyline_json, length_m, city, district, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (road_name, segment_key, start["lat"], start["lng"], end["lat"], end["lng"], json.dumps(points), length_m, city, district, now, now),
        )
        return int(cur.lastrowid)


def road_risk_score_formula(weighted_events: float, road_length_m: float) -> tuple[float, float]:
    road_length_km = max(road_length_m / 1000.0, 0.05)
    density = weighted_events / road_length_km
    score = 100.0 * (1.0 - math.exp(-density / 4.0))
    return round(clamp(score, 0, 100), 1), round(density, 3)


def recalculate_road_risk_score(road_segment_id: int) -> dict[str, Any]:
    severity = {"high": 1.0, "medium": 0.6, "low": 0.25}
    with db() as conn:
        segment = conn.execute("SELECT * FROM road_segments WHERE id = ?", (road_segment_id,)).fetchone()
        if not segment:
            return {"risk_score": 0.0, "confidence_score": 0.0}
        observations = conn.execute("SELECT * FROM road_risk_observations WHERE road_segment_id = ?", (road_segment_id,)).fetchall()
        traversals = conn.execute("SELECT COUNT(*) AS c FROM road_traversals WHERE road_segment_id = ? AND safe_pass = 1", (road_segment_id,)).fetchone()
        all_traversals = conn.execute("SELECT COUNT(*) AS c FROM road_traversals WHERE road_segment_id = ?", (road_segment_id,)).fetchone()
    now_dt = datetime.now(timezone.utc)
    weighted = 0.0
    counts = {"high": 0, "medium": 0, "low": 0}
    devices: set[str] = set()
    risk_type_weight: dict[str, float] = {}
    loc_quality_sum = 0.0
    for row in observations:
        level = str(row["risk_level"] or "low").lower()
        counts[level if level in counts else "low"] += 1
        devices.add(str(row["device_id"]))
        confidence = float(row["confidence"] or 0.5)
        accuracy = row["location_accuracy_m"]
        location_weight = 1.0 if accuracy is None else clamp(1.0 - max(0.0, float(accuracy) - 5.0) / 50.0, 0.25, 1.0)
        loc_quality_sum += location_weight
        try:
            observed = datetime.fromisoformat(str(row["observed_at"]))
        except Exception:
            observed = now_dt
        age_days = max(0.0, (now_dt - observed).total_seconds() / 86400.0)
        decay = math.exp(-age_days / 30.0)
        contribution = severity.get(level, 0.25) * confidence * location_weight * decay
        weighted += contribution
        rt = str(row["risk_type"] or "unknown")
        risk_type_weight[rt] = risk_type_weight.get(rt, 0.0) + contribution
    score, density = road_risk_score_formula(weighted, float(segment["length_m"] or 50.0))
    safe_count = int((traversals or {"c": 0})["c"] if hasattr(traversals, "keys") else 0)
    total_count = int((all_traversals or {"c": 0})["c"] if hasattr(all_traversals, "keys") else 0)
    confidence_score = clamp((len(devices) * 18 + len(observations) * 4 + safe_count * 2 + (loc_quality_sum / max(1, len(observations))) * 30), 0, 100)
    # Product constraints: one device/one event cannot create high confidence;
    # >50 needs at least two devices or repeated traversals/observations; >70
    # needs at least three devices plus safe traversal evidence.
    if len(devices) <= 1 and len(observations) <= 1:
        confidence_score = min(confidence_score, 25.0)
    if len(devices) < 2 and (len(observations) + safe_count) < 2:
        confidence_score = min(confidence_score, 50.0)
    if len(devices) < 3 or safe_count < 1:
        confidence_score = min(confidence_score, 70.0)
    if safe_count > 0:
        score = max(0.0, score - min(20.0, safe_count * 4.0))
    main_risk_type = max(risk_type_weight.items(), key=lambda item: item[1])[0] if risk_type_weight else None
    calculated_at = now_iso()
    with db() as conn:
        conn.execute(
            """
            INSERT INTO road_risk_scores (road_segment_id, risk_score, confidence_score, event_density_per_km, unique_device_count, safe_traversal_count, high_count, medium_count, low_count, main_risk_type, calculated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(road_segment_id) DO UPDATE SET
              risk_score=excluded.risk_score, confidence_score=excluded.confidence_score, event_density_per_km=excluded.event_density_per_km,
              unique_device_count=excluded.unique_device_count, safe_traversal_count=excluded.safe_traversal_count,
              high_count=excluded.high_count, medium_count=excluded.medium_count, low_count=excluded.low_count,
              main_risk_type=excluded.main_risk_type, calculated_at=excluded.calculated_at
            """,
            (road_segment_id, score, round(confidence_score, 1), density, len(devices), safe_count, counts["high"], counts["medium"], counts["low"], main_risk_type, calculated_at),
        )
    return {"risk_score": score, "confidence_score": round(confidence_score, 1), "event_density_per_km": density, "unique_device_count": len(devices), "safe_traversal_count": safe_count, "total_traversal_count": total_count, "main_risk_type": main_risk_type, "risk_point_count": len(observations)}


def road_score_for_segment(segment_id: Optional[int]) -> dict[str, Any]:
    if not segment_id:
        return {"risk_score": 0.0, "confidence_score": 0.0, "main_risk_types": [], "risk_point_count": 0}
    with db() as conn:
        row = conn.execute("SELECT * FROM road_risk_scores WHERE road_segment_id = ?", (segment_id,)).fetchone()
    if not row:
        calculated = recalculate_road_risk_score(segment_id)
        return {"risk_score": calculated.get("risk_score", 0.0), "confidence_score": calculated.get("confidence_score", 0.0), "main_risk_types": [calculated.get("main_risk_type")] if calculated.get("main_risk_type") else [], "risk_point_count": calculated.get("risk_point_count", 0)}
    return {"risk_score": float(row["risk_score"] or 0), "confidence_score": float(row["confidence_score"] or 0), "main_risk_types": [row["main_risk_type"]] if row["main_risk_type"] else [], "risk_point_count": int(row["high_count"] or 0) + int(row["medium_count"] or 0) + int(row["low_count"] or 0)}


def classify_road_risk_type(risk_type: str, report_count: int = 1) -> tuple[bool, bool]:
    if risk_type in ROAD_EXCLUDED_RISK_TYPES:
        return False, False
    if risk_type in {"ground_step_down", "ground_step_up", "ground_drop", "ground_step", "confirmed_user_mark", "fixed_obstacle"}:
        return True, True
    if risk_type in ROAD_TEMPORARY_RISK_TYPES:
        return True, False
    if risk_type in {"front_obstacle", "left_obstacle", "right_obstacle"} and report_count >= 2:
        return True, False
    return False, False


def nearest_road_segment_id(lat: float, lng: float, max_distance_m: float = 35.0) -> Optional[int]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM road_segments ORDER BY updated_at DESC LIMIT 300").fetchall()
    best_id = None
    best_dist = None
    for row in rows:
        try:
            points = json.loads(row["polyline_json"] or "[]")
        except Exception:
            points = []
        if not points:
            continue
        dist = min_distance_to_polyline_m(lat, lng, points)
        if dist <= max_distance_m and (best_dist is None or dist < best_dist):
            best_id = int(row["id"])
            best_dist = dist
    return best_id


def create_local_road_segment(lat: float, lng: float) -> int:
    points = [{"lat": lat, "lng": lng}, {"lat": lat + 0.00005, "lng": lng}]
    step = {"road": "本地测试道路", "distance": 6, "polyline": ";".join(f"{p['lng']},{p['lat']}" for p in points)}
    return upsert_road_segment_from_step(step) or 0


def maybe_store_road_observation(event: dict[str, Any]) -> None:
    risk_type = str(event.get("risk_type") or "none")
    include, is_fixed = classify_road_risk_type(risk_type, int(event.get("report_count") or 1))
    if not include:
        return
    lat = event.get("lat")
    lng = event.get("lng")
    if lat is None or lng is None:
        return
    lat = float(lat)
    lng = float(lng)
    source = str(event.get("source") or event.get("sensor") or "")
    if is_legacy_sim_point(lat, lng) or source.lower() == "mock":
        return
    segment_id = nearest_road_segment_id(lat, lng)
    match_status = "matched" if segment_id else "pending"
    observed_at = str(event.get("timestamp") or now_iso())
    expires_at = None if is_fixed else (datetime.now(timezone.utc) + timedelta(seconds=RISK_POINT_TRANSIENT_TTL_SECONDS)).isoformat(timespec="seconds")
    level = str(event.get("risk_level") or "low")
    confidence = float(event.get("confidence") or event.get("risk_score") or 0.6)
    if confidence > 1.0:
        confidence = confidence / 100.0
    with db() as conn:
        conn.execute(
            """
            INSERT INTO road_risk_observations (road_segment_id, device_id, risk_type, risk_level, confidence, lat, lng, location_accuracy_m, source, is_fixed, observed_at, expires_at, match_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (segment_id, str(event.get("device_id") or "unknown"), risk_type, level, clamp(confidence, 0.1, 1.0), lat, lng, event.get("accuracy_m"), source or None, 1 if is_fixed else 0, observed_at, expires_at, match_status),
        )
        if segment_id:
            conn.execute(
                """
                UPDATE road_traversals
                   SET risk_event_count = risk_event_count + 1, safe_pass = 0
                 WHERE id = (
                     SELECT id FROM road_traversals
                      WHERE road_segment_id = ? AND device_id = ? AND status = 'active'
                      ORDER BY id DESC LIMIT 1
                 )
                """,
                (segment_id, str(event.get("device_id") or "unknown")),
            )
    if segment_id:
        recalculate_road_risk_score(segment_id)


def match_pending_road_observations(limit: int = 200) -> int:
    matched = 0
    touched: set[int] = set()
    with db() as conn:
        rows = conn.execute(
            """
            SELECT id, lat, lng FROM road_risk_observations
             WHERE road_segment_id IS NULL OR match_status = 'pending'
             ORDER BY id DESC LIMIT ?
            """,
            (limit,),
        ).fetchall()
        segments = conn.execute("SELECT id, polyline_json FROM road_segments").fetchall()
        for observation in rows:
            best_id: Optional[int] = None
            best_distance = float("inf")
            for segment in segments:
                try:
                    points = json.loads(segment["polyline_json"] or "[]")
                except Exception:
                    continue
                distance = min_distance_to_polyline_m(float(observation["lat"]), float(observation["lng"]), points)
                if distance < best_distance:
                    best_id, best_distance = int(segment["id"]), distance
            if best_id is not None and best_distance <= 35.0:
                conn.execute(
                    """
                    UPDATE road_risk_observations
                       SET road_segment_id = ?, match_status = 'matched', matched_distance_m = ?
                     WHERE id = ?
                    """,
                    (best_id, round(best_distance, 2), int(observation["id"])),
                )
                matched += 1
                touched.add(best_id)
    for segment_id in touched:
        recalculate_road_risk_score(segment_id)
    return matched

async def route_risk_summary(points: list[dict[str, float]], buffer_m: float) -> dict[str, Any]:
    if not points:
        return {
            "risk_score": 0.0,
            "risk_points": [],
            "risk_count": 0,
            "medium_high_count": 0,
            "high_count": 0,
            "medium_count": 0,
            "max_level": "low",
        }

    risk_points: list[dict[str, Any]] = []
    score = 0.0
    for item in active_risk_points(limit=1000):
        level = str(item.get("risk_level") or item.get("riskLevel") or "low").lower()
        try:
            risk_lat, risk_lng = await convert_to_amap_coord(float(item["lat"]), float(item["lng"]), "gps")
        except Exception:
            risk_lat, risk_lng = float(item["lat"]), float(item["lng"])
        distance = min_distance_to_polyline_m(risk_lat, risk_lng, points)
        accuracy_m = float(item.get("location_accuracy_m") or item.get("locationAccuracyM") or 0)
        effective_buffer = min(30.0, max(8.0, float(buffer_m), accuracy_m))
        if distance > effective_buffer:
            continue
        base = {"high": 32, "medium": 16, "low": 5}.get(level, 5)
        proximity = (1 - distance / effective_buffer) ** 2
        contribution = base * proximity
        score += contribution
        risk_points.append(
            {
                **item,
                "risk_level": level,
                "riskLevel": level,
                "distance_to_route_m": round(distance, 1),
                "distanceToRouteM": round(distance, 1),
                "effective_buffer_m": round(effective_buffer, 1),
                "route_lat": risk_lat,
                "route_lng": risk_lng,
                "_route_lat": risk_lat,
                "_route_lng": risk_lng,
                "score_contribution": round(contribution, 1),
            }
        )

    risk_points.sort(key=lambda item: item["score_contribution"], reverse=True)
    max_level = "low"
    for item in risk_points:
        if LEVEL_RANK[item["risk_level"]] > LEVEL_RANK[max_level]:
            max_level = item["risk_level"]
    return {
        "risk_score": round(clamp(score, 0, 100), 1),
        "risk_points": risk_points[:20],
        "risk_count": len(risk_points),
        "medium_high_count": sum(1 for item in risk_points if item["risk_level"] in {"medium", "high"}),
        "high_count": sum(1 for item in risk_points if item["risk_level"] == "high"),
        "medium_count": sum(1 for item in risk_points if item["risk_level"] == "medium"),
        "max_level": max_level,
    }


async def plan_walking_route(
    origin_lat: float,
    origin_lng: float,
    destination_lat: float,
    destination_lng: float,
    origin_coordsys: str,
    destination_coordsys: str,
) -> dict[str, Any]:
    origin_amap_lat, origin_amap_lng = await convert_to_amap_coord(origin_lat, origin_lng, origin_coordsys)
    dest_amap_lat, dest_amap_lng = await convert_to_amap_coord(destination_lat, destination_lng, destination_coordsys)
    data = await amap_get(
        "/direction/walking",
        {
            "origin": amap_location(origin_amap_lng, origin_amap_lat),
            "destination": amap_location(dest_amap_lng, dest_amap_lat),
        },
    )
    return {
        "input": {
            "origin": {"lat": origin_lat, "lng": origin_lng, "coordsys": origin_coordsys},
            "destination": {"lat": destination_lat, "lng": destination_lng, "coordsys": destination_coordsys},
        },
        "amap_origin": {"lat": origin_amap_lat, "lng": origin_amap_lng},
        "amap_destination": {"lat": dest_amap_lat, "lng": dest_amap_lng},
        "raw": data,
    }


async def enrich_walking_route(route: dict[str, Any], buffer_m: float) -> dict[str, Any]:
    raw_paths = (route.get("raw", {}).get("route") or {}).get("paths") or []
    paths: list[dict[str, Any]] = []
    for index, path in enumerate(raw_paths):
        points = route_points_from_path(path)
        risk = await route_risk_summary(points, buffer_m)
        distance_m = int(float(path.get("distance") or 0))
        duration_s = int(float(path.get("duration") or 0))
        enriched_steps = []
        weighted_road_score = 0.0
        weighted_len = 0.0
        max_hotspot = float(risk["risk_score"] or 0)
        for step_index, step in enumerate(path.get("steps", [])):
            step_copy = dict(step)
            segment_id = upsert_road_segment_from_step(step_copy)
            road_score = road_score_for_segment(segment_id)
            step_distance = float(step_copy.get("distance") or 0)
            weighted_road_score += float(road_score.get("risk_score") or 0) * step_distance
            weighted_len += step_distance
            max_hotspot = max(max_hotspot, float(road_score.get("risk_score") or 0))
            step_copy.update({
                "step_index": step_index,
                "road_segment_id": segment_id,
                "road_name": step_copy.get("road") or "未命名道路",
                "distance_m": int(step_distance),
                "risk_score": road_score.get("risk_score", 0.0),
                "confidence_score": road_score.get("confidence_score", 0.0),
                "main_risk_types": road_score.get("main_risk_types", []),
                "risk_point_count": road_score.get("risk_point_count", 0),
            })
            enriched_steps.append(step_copy)
        match_pending_road_observations()
        avg_road_score = weighted_road_score / weighted_len if weighted_len > 0 else 0.0
        for matched in risk.get("risk_points", []):
            nearest_step_index = 0
            nearest_step_distance = float("inf")
            for candidate_index, candidate_step in enumerate(enriched_steps):
                candidate_points = parse_polyline(str(candidate_step.get("polyline") or ""))
                if not candidate_points:
                    continue
                candidate_distance = min_distance_to_polyline_m(
                    float(matched.get("_route_lat", matched.get("lat"))),
                    float(matched.get("_route_lng", matched.get("lng"))),
                    candidate_points,
                )
                if candidate_distance < nearest_step_distance:
                    nearest_step_distance = candidate_distance
                    nearest_step_index = candidate_index
            nearest_step = enriched_steps[nearest_step_index] if enriched_steps else {}
            matched["nearest_step_index"] = nearest_step_index
            matched["nearest_road_segment_id"] = nearest_step.get("road_segment_id")
            matched.pop("_route_lat", None)
            matched.pop("_route_lng", None)
        route_risk = round(0.7 * avg_road_score + 0.3 * max_hotspot, 1)
        risk["route_risk_score"] = route_risk
        paths.append(
            {
                "index": index,
                "distance_m": distance_m,
                "duration_s": duration_s,
                "risk_score": route_risk,
                "combined_score": round(route_risk + distance_m / 1000.0 * 3 + duration_s / 600.0, 1),
                "risk": risk,
                "steps": enriched_steps,
                "polyline": points,
            }
        )
    shortest = min(paths, key=lambda item: (item["distance_m"], item["duration_s"])) if paths else None
    eligible = [
        item for item in paths
        if shortest and item["distance_m"] <= max(shortest["distance_m"] * 1.30, shortest["distance_m"] + 1)
    ]
    eligible.sort(key=lambda item: (
        int(item["risk"].get("high_count") or 0),
        int(item["risk"].get("medium_count") or 0),
        float(item["risk"].get("risk_score") or 0),
        int(item["risk"].get("risk_count") or 0),
        float(item.get("risk_score") or 0),
        item["distance_m"],
        item["duration_s"],
    ))
    best = eligible[0] if eligible else shortest
    return {
        "routes": paths,
        "best_route": best,
        "shortest_route": shortest,
        "selected_route_index": best.get("index") if best else None,
        "route_count": len(paths),
        "voice_prompt": route_voice_prompt(best),
    }



def route_distance_text(distance_m: int) -> str:
    if distance_m < 1000:
        return f"{distance_m}米"
    return f"{distance_m / 1000:.1f}公里"


def route_direction_text(best: Optional[dict[str, Any]], max_steps: int = 4) -> str:
    if not best:
        return ""
    parts: list[str] = []
    for step in best.get("steps") or []:
        try:
            distance_m = int(float(step.get("distance") or 0))
        except (TypeError, ValueError):
            distance_m = 0
        if distance_m <= 0:
            continue
        road = str(step.get("road") or "").strip()
        instruction = re.sub(r"<[^>]+>", "", str(step.get("instruction") or "")).strip()
        if road:
            if "左转" in instruction:
                text = f"左转沿{road}走{distance_m}米"
            elif "右转" in instruction:
                text = f"右转沿{road}走{distance_m}米"
            else:
                text = f"沿{road}直行{distance_m}米"
        elif instruction:
            text = instruction
            if str(distance_m) not in text:
                text = f"{text}{distance_m}米"
        else:
            continue
        if parts and parts[-1] == text:
            continue
        parts.append(text)
        if len(parts) >= max_steps:
            break
    if not parts:
        return ""
    return "，".join(parts) + "，即可到达"


def route_risk_text(risk: dict[str, Any], buffer_m: int = 8) -> str:
    high_count = int(risk.get("high_count") or 0)
    medium_count = int(risk.get("medium_count") or 0)
    medium_high_count = high_count + medium_count
    if medium_high_count > 0:
        return f"路线周围{buffer_m}米内有{medium_high_count}个中高风险点，其中高风险{high_count}个，中风险{medium_count}个"
    return f"路线周围{buffer_m}米内暂无中高风险点"


# Final product-mode Chinese overrides. Keep these after the legacy compatibility
# helpers so firmware, Android, and backend all speak clear text.
def alert_title(risk_type: str) -> str:
    return {
        "sos": "\u7528\u6237\u4e3b\u52a8 SOS",
        "fall_detected": "\u7591\u4f3c\u8dcc\u5012\u544a\u8b66",
        "voice_request": "\u8bed\u97f3\u4ea4\u4e92\u8bf7\u6c42",
        "prolonged_obstacle": "\u6301\u7eed\u969c\u788d\u63d0\u9192",
        "approaching_obstacle": "\u969c\u788d\u903c\u8fd1\u63d0\u9192",
    }.get(risk_type, "\u98ce\u9669\u544a\u8b66")


def voice_prompt_for_risk(frame: SensorFrameCreate, risk_type: str, level: str, direction: str) -> str:
    if risk_type == "voice_request":
        return "\u5df2\u6536\u5230\u76f2\u6756\u6309\u94ae\u8bf7\u6c42\uff0c\u8bf7\u8bf4\u51fa\u76ee\u7684\u5730\u6216\u64cd\u4f5c\u6307\u4ee4\u3002"
    if risk_type == "sos":
        return "SOS \u5df2\u53d1\u9001\uff0c\u5df2\u901a\u77e5\u966a\u62a4\u7aef\uff0c\u8bf7\u505c\u5728\u5b89\u5168\u4f4d\u7f6e\u7b49\u5f85\u8054\u7cfb\u3002"
    if risk_type == "fall_detected":
        return "\u68c0\u6d4b\u5230\u7591\u4f3c\u8dcc\u5012\uff0c\u5df2\u901a\u77e5\u76f2\u4eba\u7aef\u548c\u966a\u62a4\u7aef\uff0c\u8bf7\u4fdd\u6301\u539f\u5730\u3002"
    if risk_type == "prolonged_obstacle":
        return "\u540c\u4e00\u969c\u788d\u6301\u7eed\u51fa\u73b0\uff0c\u5df2\u901a\u77e5\u966a\u62a4\u7aef\uff0c\u8bf7\u505c\u6b62\u5e76\u91cd\u65b0\u63a2\u6d4b\u3002"
    if risk_type == "approaching_obstacle":
        return "\u969c\u788d\u8ddd\u79bb\u6b63\u5728\u7f29\u77ed\uff0c\u8bf7\u7acb\u5373\u51cf\u901f\uff0c\u5fc5\u8981\u65f6\u505c\u6b62\u3002"
    if risk_type == "ground_step_down":
        return "检测到前方落差，请立即停下并探测台阶。"
    if risk_type == "ground_step_up":
        return "检测到前方上台阶，请停下并抬脚确认。"
    if risk_type in {"ground_drop", "ground_step"}:
        return "检测到前方落差，请立即停下并探测台阶。"
    if risk_type == "down_no_target":
        return ""
    if risk_type == "down_sensor_unavailable":
        return "下视传感器异常，请停下检查。"
    if risk_type == "down_obstacle":
        return ""
    if risk_type == "front_obstacle":
        if direction == "turn_left":
            return f"\u524d\u65b9 {frame.front_cm or '-'} \u5398\u7c73\u6709\u969c\u788d\uff0c\u5de6\u4fa7\u76f8\u5bf9\u66f4\u5b89\u5168\u3002"
        if direction == "turn_right":
            return f"\u524d\u65b9 {frame.front_cm or '-'} \u5398\u7c73\u6709\u969c\u788d\uff0c\u53f3\u4fa7\u76f8\u5bf9\u66f4\u5b89\u5168\u3002"
        return f"\u524d\u65b9 {frame.front_cm or '-'} \u5398\u7c73\u6709\u969c\u788d\uff0c\u8bf7\u505c\u6b62\u786e\u8ba4\u3002"
    if risk_type == "left_obstacle":
        return f"\u5de6\u4fa7 {frame.left_cm or '-'} \u5398\u7c73\u6709\u969c\u788d\uff0c\u8bf7\u5411\u53f3\u4fdd\u6301\u8ddd\u79bb\u3002"
    if risk_type == "right_obstacle":
        return f"\u53f3\u4fa7 {frame.right_cm or '-'} \u5398\u7c73\u6709\u969c\u788d\uff0c\u8bf7\u5411\u5de6\u4fdd\u6301\u8ddd\u79bb\u3002"
    if risk_type == "history_risk":
        return "\u9644\u8fd1\u6709\u591a\u4eba\u8bb0\u5f55\u7684\u5386\u53f2\u98ce\u9669\u70b9\uff0c\u8bf7\u51cf\u901f\u786e\u8ba4\u3002"
    if risk_type == "user_mark":
        return "\u98ce\u9669\u70b9\u5df2\u8bb0\u5f55\uff0c\u5e76\u4f1a\u540c\u6b65\u7ed9\u540e\u7eed\u7528\u6237\u3002"
    return "\u5f53\u524d\u672a\u53d1\u73b0\u660e\u663e\u969c\u788d\uff0c\u8bf7\u7ee7\u7eed\u8c28\u614e\u524d\u8fdb\u3002"


def route_voice_prompt(best: Optional[dict[str, Any]]) -> str:
    if not best:
        return "暂时没有找到可用的步行路线。"
    risk = best.get("risk", {})
    distance_m = int(best.get("distance_m") or 0)
    base = f"导航开始，全程约{route_distance_text(distance_m)}，{route_risk_text(risk)}"
    direction = route_direction_text(best)
    if direction:
        return f"{base}。首先{direction}。"
    return f"{base}。请按路线谨慎前进。"


def parse_route_text(text: str) -> tuple[Optional[str], Optional[str]]:
    normalized = text.strip()
    match = re.search(r"\u4ece(.+?)(?:\u5230|\u53bb|\u524d\u5f80)(.+)", normalized)
    if match:
        return match.group(1).strip(" ，。,"), match.group(2).strip(" ，。,")
    match = re.search(r"(?:\u5bfc\u822a\u5230|\u5e26\u6211\u53bb|\u53bb|\u524d\u5f80)(.+)", normalized)
    if match:
        return None, match.group(1).strip(" ，。,")
    return None, None


def is_plain_greeting(text: str) -> bool:
    normalized = re.sub(r"[\s，。！？!?,.]", "", text.strip().lower())
    return normalized in {
        "你好",
        "你好啊",
        "您好",
        "您好啊",
        "hello",
        "hi",
        "嗨",
        "哈喽",
    }


def parse_json_object(text: str) -> dict[str, Any]:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start >= 0 and end > start:
        cleaned = cleaned[start : end + 1]
    return json.loads(cleaned)


async def parse_route_text_with_llm(text: str) -> dict[str, Any]:
    fallback_origin, fallback_destination = parse_route_text(text)
    if is_plain_greeting(text):
        return {
            "origin_text": None,
            "destination_text": None,
            "intent": "unknown",
            "confidence": 1.0,
            "reply": "你好，我在。请按住说出要导航去的地方。",
            "fallback": True,
        }
    fallback = {
        "origin_text": fallback_origin,
        "destination_text": fallback_destination,
        "intent": "route" if fallback_destination else "unknown",
        "confidence": 0.45 if fallback_destination else 0.2,
        "reply": None if fallback_destination else "请说出要导航去的地方，例如：导航到南开大学图书馆。",
        "fallback": True,
    }
    messages = [
        {
            "role": "system",
            "content": (
                "你是智能盲杖的语音指令解析器。"
                "只返回 JSON，不要 Markdown。字段：intent, origin_text, destination_text, confidence, reply。"
                "intent 可选 route/query_risk/repeat/sos/unknown。"
                "如果用户想导航，把 destination_text 提取成可用于高德地理编码的中文地点名。"
                "如果用户说从某地到某地，同时填写 origin_text 和 destination_text。"
                "问候、闲聊、无目的地的话必须返回 unknown，destination_text 为空。"
            ),
        },
        {"role": "user", "content": json.dumps({"text": text}, ensure_ascii=False)},
    ]
    try:
        content, meta = await call_chat_completion(messages, temperature=0.0)
        if not content:
            return {**fallback, "provider": meta["provider"], "model": meta["model"]}
        parsed = parse_json_object(content)
        intent = parsed.get("intent") or fallback["intent"]
        origin_text = parsed.get("origin_text") or fallback_origin
        destination_text = parsed.get("destination_text") if intent == "route" else None
        destination_text = destination_text or (fallback_destination if intent == "route" else None)
        if intent == "route" and not destination_text:
            intent = "unknown"
        return {
            "origin_text": origin_text,
            "destination_text": destination_text,
            "intent": intent,
            "confidence": parsed.get("confidence", 0.0),
            "reply": parsed.get("reply") or (None if destination_text else fallback["reply"]),
            "fallback": False,
            "provider": meta["provider"],
            "model": meta["model"],
        }
    except Exception as exc:
        return {**fallback, "error": str(exc), "provider": chat_config()["provider"], "model": chat_config()["model"]}


async def resolve_route_endpoint(request: MapRouteRequest) -> tuple[float, float, float, float, dict[str, Any]]:
    origin_meta: dict[str, Any] = {}
    dest_meta: dict[str, Any] = {}
    origin_lat = request.origin_lat
    origin_lng = request.origin_lng
    dest_lat = request.destination_lat
    dest_lng = request.destination_lng

    if (origin_lat is None or origin_lng is None) and request.origin_text:
        origin_meta = await geocode_address(request.origin_text, request.city)
        origin_lat, origin_lng = origin_meta["lat"], origin_meta["lng"]
    elif origin_lat is not None and origin_lng is not None:
        origin_meta = {"source": "request_coordinate", "coordsys": request.coordsys}
    if (origin_lat is None or origin_lng is None) and request.device_id:
        latest = latest_location_for_device(request.device_id)
        if latest:
            origin_lat, origin_lng = float(latest["lat"]), float(latest["lng"])
            origin_meta = {"source": "latest_device_location", "device_id": request.device_id, "coordsys": request.coordsys}
    if origin_lat is None or origin_lng is None:
        raise HTTPException(status_code=400, detail="origin coordinate, origin_text, or device latest location is required")

    origin_coordsys = str(origin_meta.get("coordsys") or request.coordsys)
    if (dest_lat is None or dest_lng is None) and request.destination_text:
        dest_meta = await resolve_destination_address(
            request.destination_text,
            request.city,
            origin_lat,
            origin_lng,
            origin_coordsys,
        )
        dest_lat, dest_lng = dest_meta["lat"], dest_meta["lng"]
    elif dest_lat is not None and dest_lng is not None:
        dest_meta = {"source": "request_coordinate", "coordsys": request.coordsys}
    if dest_lat is None or dest_lng is None:
        raise HTTPException(status_code=400, detail="destination coordinate or destination_text is required")
    return origin_lat, origin_lng, dest_lat, dest_lng, {"origin": origin_meta, "destination": dest_meta}


def chat_config() -> dict[str, str]:
    provider = env("LLM_PROVIDER", "ark").lower()
    if provider in {"vei", "volces", "volcengine", "ai_gateway"}:
        return {
            "provider": "vei",
            "api_key": env("VEI_API_KEY"),
            "base_url": env("VEI_BASE_URL", env("VEI_OPENAI_BASE_URL", "https://ai-gateway.vei.volces.com/v1")).rstrip("/"),
            "model": env("VEI_MODEL", "doubao-1.5-lite-32k"),
        }
    if provider == "openai":
        return {
            "provider": "openai",
            "api_key": secret_env("OPENAI_API_KEY"),
            "base_url": env("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
            "model": env("OPENAI_MODEL", "gpt-4.1-mini"),
        }
    return {
        "provider": "ark",
        "api_key": secret_env("ARK_API_KEY"),
        "base_url": env("ARK_OPENAI_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3").rstrip("/"),
        "model": env("ARK_MODEL", "doubao-seed-2-1-pro-260628"),
    }


def stt_config() -> dict[str, str]:
    provider = env("STT_PROVIDER", "openai").lower()
    if provider in {"volc_seedasr", "seedasr", "doubao_stt", "volc_asr", "volcengine_asr"}:
        return {
            "provider": "volc_seedasr",
            "api_key": env("VOLC_ASR_API_KEY", env("DOUBAO_STT_API_KEY", env("SEEDASR_API_KEY", env("STT_API_KEY", "")))),
            "app_key": env("VOLC_ASR_APP_KEY", env("DOUBAO_STT_APP_ID", env("VOLC_ASR_APP_ID", ""))),
            "access_key": env("VOLC_ASR_ACCESS_KEY", env("DOUBAO_STT_ACCESS_KEY", env("VOLC_ASR_ACCESS_TOKEN", ""))),
            "resource_id": env("VOLC_ASR_RESOURCE_ID", "volc.seedasr.auc"),
            "submit_url": env("VOLC_ASR_SUBMIT_URL", "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"),
            "query_url": env("VOLC_ASR_QUERY_URL", "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"),
            "model": env("VOLC_ASR_MODEL", "bigmodel"),
            "poll_interval_sec": env("VOLC_ASR_POLL_INTERVAL_SEC", "1.0"),
            "max_polls": env("VOLC_ASR_MAX_POLLS", "20"),
        }
    if provider in {"vei", "volces", "volcengine", "ai_gateway"}:
        return {
            "provider": "vei",
            "api_key": env("VEI_API_KEY"),
            "base_url": env("VEI_BASE_URL", env("VEI_OPENAI_BASE_URL", "https://ai-gateway.vei.volces.com/v1")).rstrip("/"),
            "model": env("VEI_STT_MODEL", env("STT_MODEL", "")),
        }
    if provider == "ark":
        return {
            "provider": "ark",
            "api_key": secret_env("ARK_API_KEY"),
            "base_url": env("ARK_OPENAI_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3").rstrip("/"),
            "model": env("ARK_STT_MODEL", env("STT_MODEL", "")),
        }
    return {
        "provider": "openai",
        "api_key": secret_env("OPENAI_API_KEY"),
        "base_url": env("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/"),
        "model": env("OPENAI_STT_MODEL", env("STT_MODEL", "whisper-1")),
    }


def ai_enabled() -> bool:
    cfg = chat_config()
    return bool(cfg["api_key"] and cfg["model"] and cfg["base_url"])


def fallback_advice(req: AdviceRequest, history: dict[str, Any]) -> str:
    if req.risk_type == "sos":
        return "SOS already sent. Stay where you are if safe."
    if req.risk_type in {"ground_drop", "ground_step", "down_no_target", "down_sensor_unavailable"} or (req.down_cm is not None and req.down_cm > DOWN_DROP_CM):
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


def deep_advice(req: AdviceRequest, deep: dict[str, Any]) -> str:
    level = deep.get("level", "low")
    if level == "high":
        if req.risk_type in {"ground_drop", "ground_step", "down_no_target", "down_sensor_unavailable"} or (req.down_cm is not None and req.down_cm > DOWN_DROP_CM):
            return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u843d\u5dee\u98ce\u9669\uff0c\u8bf7\u505c\u6b62\u63a2\u8def\u3002"
        if req.left_cm is not None and req.right_cm is not None:
            if req.left_cm > req.right_cm and req.left_cm > 90:
                return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u9ad8\u98ce\u9669\uff0c\u8bf7\u5411\u5de6\u6162\u884c\u3002"
            if req.right_cm > req.left_cm and req.right_cm > 90:
                return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u9ad8\u98ce\u9669\uff0c\u8bf7\u5411\u53f3\u6162\u884c\u3002"
        return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u9ad8\u98ce\u9669\uff0c\u8bf7\u505c\u6b62\u3002"
    if level == "medium":
        return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u4e2d\u98ce\u9669\uff0c\u8bf7\u51cf\u901f\u786e\u8ba4\u3002"
    return "\u6df1\u5ea6\u6a21\u578b\u63d0\u793a\u98ce\u9669\u8f83\u4f4e\uff0c\u8bf7\u8c28\u614e\u524d\u8fdb\u3002"


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


async def generate_advice(req: AdviceRequest, history: dict[str, Any], deep: dict[str, Any]) -> dict[str, Any]:
    fallback = deep_advice(req, deep) if deep.get("level") != "low" else fallback_advice(req, history)
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
                    "deep_learning": {
                        "model": deep["model"],
                        "score": deep["score"],
                        "level": deep["level"],
                        "confidence": deep["confidence"],
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
    if any(word in normalized for word in ["sos", "help", "\u6551\u547d", "\u6c42\u52a9"]):
        return {"intent": "sos", "action": "trigger_sos", "confidence": 0.95, "reply": "\u5df2\u8bc6\u522b\u6c42\u52a9\u6307\u4ee4"}
    if any(word in normalized for word in ["upload", "mark", "record", "\u6807\u8bb0", "\u8bb0\u5f55"]):
        return {"intent": "mark_risk", "action": "upload_user_mark", "confidence": 0.8, "reply": "\u5df2\u8bc6\u522b\u98ce\u9669\u6807\u8bb0\u6307\u4ee4"}
    if any(word in normalized for word in ["nearby", "risk", "\u9644\u8fd1", "\u98ce\u9669"]):
        return {"intent": "query_risk", "action": "query_nearby_risks", "confidence": 0.75, "reply": "\u5df2\u8bc6\u522b\u98ce\u9669\u67e5\u8be2\u6307\u4ee4"}
    if any(word in normalized for word in ["repeat", "again", "\u91cd\u590d", "\u518d\u8bf4"]):
        return {"intent": "repeat", "action": "repeat_last_prompt", "confidence": 0.7, "reply": "\u5df2\u8bc6\u522b\u91cd\u590d\u63d0\u793a\u6307\u4ee4"}
    return {"intent": "unknown", "action": "none", "confidence": 0.35, "reply": "\u672a\u8bc6\u522b\u660e\u786e\u6307\u4ee4"}


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
    cutoff = (datetime.now(timezone.utc) - timedelta(minutes=2)).isoformat(timespec="seconds")
    with db() as conn:
        conn.execute(
            """
            UPDATE road_traversals
            SET status = 'incomplete', safe_pass = 0, finished_at = ?, duration_seconds =
                MAX(0, (julianday(?) - julianday(started_at)) * 86400.0)
            WHERE status = 'active' AND started_at < ?
            """,
            (now_iso(), now_iso(), cutoff),
        )


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "time": now_iso(), "database": str(DB_PATH)}


@app.post("/api/auth/register", status_code=201)
def register_user(request: AuthRegisterRequest) -> dict[str, Any]:
    account = normalize_account(request.account)
    created_at = now_iso()
    salt = secrets.token_hex(16)
    password_hash = hash_password(request.password, salt)
    user_id = f"user_{uuid.uuid4().hex[:12]}"
    try:
        with db() as conn:
            conn.execute(
                """
                INSERT INTO users (user_id, account, display_name, role, password_salt, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (user_id, account, request.displayName.strip(), request.role, salt, password_hash, created_at, created_at),
            )
            row = conn.execute("SELECT * FROM users WHERE user_id = ?", (user_id,)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(status_code=409, detail="账号已存在")
    return {"success": True, "message": "注册成功", "user": public_user(row)}


@app.post("/api/auth/login")
def login_user(request: AuthLoginRequest) -> dict[str, Any]:
    account = normalize_account(request.account)
    with db() as conn:
        row = conn.execute("SELECT * FROM users WHERE account = ?", (account,)).fetchone()
    if not row or hash_password(request.password, row["password_salt"]) != row["password_hash"]:
        raise HTTPException(status_code=401, detail="账号或密码错误")
    return {"success": True, "message": "登录成功", "user": public_user(row)}


@app.get("/api/hardware/profile")
def hardware_profile() -> dict[str, Any]:
    return {
        "hardware": HARDWARE_PROFILE,
        "feedback_policy": {
            "front_or_side_obstacle": "2 short beeps when high risk, plus directional motor cue",
            "ground_drop": "3 urgent beeps, all motors, stop prompt",
            "sos": "5 beeps, all motors, emergency event",
            "low_risk": "no buzzer, serial/frontend status only",
        },
        "backend_note": (
            "ESP32-C5 performs local sensor safety. Backend records risk, "
            "scores collaborative history, and proxies Amap navigation."
        ),
    }


@app.get("/api/ai/status")
def ai_status() -> dict[str, Any]:
    chat = chat_config()
    stt = stt_config()
    return {
        "llm_provider": chat["provider"],
        "llm_model": chat["model"],
        "llm_configured": bool(chat["api_key"]),
        "deep_learning_model": "tiny-mlp-risk-v1",
        "deep_learning_enabled": True,
        "stt_provider": stt["provider"],
        "stt_model": stt["model"],
        "stt_configured": bool(stt["api_key"] and stt["model"]),
        "amap_configured": amap_configured(),
    }


FALL_EXCLUSIVE_SECONDS = 30


def activate_fall_suppression(device_id: str, fall_event_id: Optional[str]) -> None:
    now = datetime.now(timezone.utc)
    until_at = (now + timedelta(seconds=FALL_EXCLUSIVE_SECONDS)).isoformat(timespec="seconds")
    with db() as conn:
        conn.execute(
            """
            INSERT INTO device_alert_suppression(device_id, fall_event_id, until_at, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                fall_event_id=excluded.fall_event_id,
                until_at=excluded.until_at,
                updated_at=excluded.updated_at
            """,
            (device_id, fall_event_id, until_at, now.isoformat(timespec="seconds")),
        )


def fall_suppression_active(device_id: str) -> bool:
    with db() as conn:
        row = conn.execute(
            "SELECT until_at FROM device_alert_suppression WHERE device_id = ?",
            (device_id,),
        ).fetchone()
    if not row:
        return False
    try:
        return datetime.fromisoformat(str(row["until_at"]).replace("Z", "+00:00")) > datetime.now(timezone.utc)
    except ValueError:
        return False


def store_event(event: EventCreate) -> dict[str, Any]:
    timestamp = event.timestamp or now_iso()
    risk_level = (event.risk_level or event.level or "").lower()
    if risk_level not in LEVEL_RANK:
        raise HTTPException(status_code=400, detail="risk_level or level must be low, medium, or high")
    battery = event.battery if event.battery is not None else event.battery_percent

    normalized_fall_id = (event.fall_event_id or "").strip()
    with db() as conn:
        if normalized_fall_id:
            existing = conn.execute(
                "SELECT * FROM risk_events WHERE fall_event_id = ? LIMIT 1",
                (normalized_fall_id,),
            ).fetchone()
            if existing:
                return event_to_dict(existing)
        cur = conn.execute(
            """
            INSERT INTO risk_events (
                device_id, timestamp, lat, lng, risk_type, risk_level,
                direction, sensor, distance_mm, battery,
                front_cm, left_cm, right_cm, down_cm,
                risk_score, confidence, fall_event_id, voice_prompt, feedback_json, extra_json
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event.device_id,
                timestamp,
                event.lat,
                event.lng,
                event.risk_type,
                risk_level,
                event.direction,
                event.sensor,
                event.distance_mm,
                battery,
                event.front_cm,
                event.left_cm,
                event.right_cm,
                event.down_cm,
                event.risk_score,
                event.confidence,
                normalized_fall_id or None,
                event.voice_prompt,
                normalize_extra(event.feedback_json),
                normalize_extra(event.extra_json),
            ),
        )
        row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (cur.lastrowid,)).fetchone()

    stored = event_to_dict(row)
    if event.device_name:
        stored["device_name"] = event.device_name
        stored["deviceName"] = event.device_name
    upsert_device_state_from_event(stored)
    upsert_risk_point_for_event(stored)
    maybe_store_road_observation(stored)
    if event.risk_type == "fall_detected":
        activate_fall_suppression(event.device_id, normalized_fall_id or None)
    return stored


@app.post("/api/risk-events", status_code=201)
def create_risk_event(event: EventCreate) -> dict[str, Any]:
    lat, lng = prefer_mobile_location(event.device_id, event.lat, event.lng)
    if lat != event.lat or lng != event.lng:
        event = event.model_copy(update={"lat": lat, "lng": lng})
    return store_event(event)


@app.post("/api/events", status_code=201)
def create_event(event: EventCreate) -> dict[str, Any]:
    lat, lng = prefer_mobile_location(event.device_id, event.lat, event.lng)
    if lat != event.lat or lng != event.lng:
        event = event.model_copy(update={"lat": lat, "lng": lng})
    return store_event(event)


@app.get("/api/events")
def list_events(limit: int = Query(200, ge=1, le=1000)) -> list[dict[str, Any]]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM risk_events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return [event_to_dict(row) for row in rows]


@app.get("/api/events/latest")
def latest_event(
    device_id: Optional[str] = Query(None),
    raw: bool = Query(False),
) -> dict[str, Any]:
    query = "SELECT * FROM risk_events"
    params: tuple[Any, ...] = ()
    if device_id:
        query += " WHERE device_id = ?"
        params = (device_id,)
    query += " ORDER BY id DESC LIMIT 1"
    with db() as conn:
        row = conn.execute(query, params).fetchone()
    if not row:
        return {"found": False, "event": None}
    return {"found": True, "event": event_to_dict(row) if raw else mobile_event_dict(row)}


@app.get("/api/risk-events")
def list_risk_events(limit: int = Query(200, ge=1, le=1000)) -> list[dict[str, Any]]:
    return list_events(limit)


@app.get("/api/events/stream")
async def stream_events(
    request: Request,
    sinceId: int = Query(0, ge=0),
    role: str = Query("blind", pattern="^(blind|companion)$"),
) -> StreamingResponse:
    async def event_generator():
        last_id = sinceId
        while not await request.is_disconnected():
            with db() as conn:
                rows = conn.execute(
                    "SELECT * FROM risk_events WHERE id > ? ORDER BY id ASC LIMIT 50",
                    (last_id,),
                ).fetchall()
            if rows:
                for row in rows:
                    last_id = max(last_id, int(row["id"]))
                    payload = alert_event_payload(row, role)
                    yield f"event: risk\nid: {last_id}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"
            else:
                yield f"event: ping\ndata: {json.dumps({'time': now_iso()}, ensure_ascii=False)}\n\n"
            await asyncio.sleep(2.0)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.post("/api/locations", status_code=201)
def create_location(location: LocationCreate) -> dict[str, Any]:
    timestamp = location.timestamp or now_iso()
    with db() as conn:
        cur = conn.execute(
            """
            INSERT INTO device_locations (
                device_id, timestamp, lat, lng, source, provider, quality,
                accuracy_m, hdop, fix_quality, satellite_count
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                location.device_id,
                timestamp,
                location.lat,
                location.lng,
                location.source,
                location.provider,
                location.quality,
                location.accuracy_m,
                location.hdop,
                location.fix_quality,
                location.satellite_count,
            ),
        )
        row = conn.execute("SELECT * FROM device_locations WHERE id = ?", (cur.lastrowid,)).fetchone()
    return row_to_dict(row)


@app.post("/api/sensor-frames", status_code=201)
def create_sensor_frame(frame: SensorFrameCreate, lite: bool = Query(False)) -> dict[str, Any]:
    frame_location_is_mock = (
        str(frame.location_provider or "").lower() == "mock"
        or str(frame.location_quality or "").lower() == "mock"
    )
    if frame_location_is_mock:
        latest_mobile = latest_mobile_location_for_device(frame.device_id)
        if latest_mobile:
            lat, lng = float(latest_mobile["lat"]), float(latest_mobile["lng"])
        else:
            lat, lng = resolve_legacy_location(frame.device_id, frame.lat, frame.lng)
    else:
        lat, lng = prefer_mobile_location(frame.device_id, frame.lat, frame.lng)

    if frame.lat is not None and frame.lng is not None and not frame_location_is_mock:
        create_location(
            LocationCreate(
                device_id=frame.device_id,
                lat=frame.lat,
                lng=frame.lng,
                source=frame.source,
                provider=frame.location_provider or "esp32c5",
                quality=frame.location_quality or "usable",
                timestamp=frame.timestamp or now_iso(),
            )
        )

    history = nearby_summary(lat, lng, DEFAULT_NEARBY_RADIUS_M)
    analysis = analyze_sensor_frame(frame, history)
    if analysis["risk_type"] not in {"fall_detected", "sos"} and fall_suppression_active(frame.device_id):
        analysis.update(
            {
                "risk_type": "none",
                "risk_level": "low",
                "risk_score": 0.0,
                "map_weight": 0.0,
                "mapWeight": 0.0,
                "risk_reason": "suppressed for 30 seconds after fall",
                "riskReason": "suppressed for 30 seconds after fall",
                "risk_source_detail": "suppressed for 30 seconds after fall",
                "riskSourceDetail": "suppressed for 30 seconds after fall",
                "direction": "none",
                "sensor": "none",
                "distance_mm": None,
                "voice_prompt": "",
                "feedback": feedback_for_risk("none", "low", "none"),
            }
        )
    device_state = upsert_device_state(frame, lat, lng, analysis)
    stored_event: Optional[dict[str, Any]] = None
    if should_store_sensor_analysis(analysis):
        stored_event = store_event(
            EventCreate(
                device_id=frame.device_id,
                lat=lat,
                lng=lng,
                risk_type=analysis["risk_type"],
                risk_level=analysis["risk_level"],
                level=analysis["risk_level"],
                direction=analysis["direction"],
                sensor=analysis["sensor"],
                distance_mm=analysis["distance_mm"],
                battery=frame.battery,
                front_cm=frame.front_cm,
                left_cm=frame.left_cm,
                right_cm=frame.right_cm,
                down_cm=frame.down_cm,
                risk_score=analysis["risk_score"],
                confidence=analysis.get("confidence"),
                fall_event_id=frame.fall_event_id,
                voice_prompt=analysis["voice_prompt"],
                feedback_json=analysis["feedback"],
                extra_json={
                    "source": frame.source,
                    "device_name": frame.device_name,
                    "firmware_build": frame.firmware_build,
                    "button_event": frame.button_event,
                    "touch_electrode": frame.touch_electrode,
                    "touch_event": frame.touch_event,
                    "alert_type": frame.alert_type,
                    "fall_detected": frame.fall_detected,
                    "fall_stage": frame.fall_stage,
                    "fall_confidence": frame.fall_confidence,
                    "accel_x_g": frame.accel_x_g,
                    "accel_y_g": frame.accel_y_g,
                    "accel_z_g": frame.accel_z_g,
                    "accel_total_g": frame.accel_total_g,
                    "hardware_profile": "esp32c5_tca_ch2_3_4_5_mpr121_ch7_pca9685_ch0_1_2_bmi270",
                    "risk_reason": analysis.get("risk_reason"),
                    "riskReason": analysis.get("riskReason"),
                    "map_weight": analysis.get("map_weight"),
                    "mapWeight": analysis.get("mapWeight"),
                    "nearby_history": analysis["nearby_history"],
                    "extra": frame.extra,
                },
                timestamp=frame.timestamp or now_iso(),
            )
        )
        print(
            "[SMARTCANE] event "
            f"id={stored_event['id']} device={stored_event['device_id']} "
            f"type={stored_event['risk_type']} level={stored_event['risk_level']} "
            f"map_weight={analysis.get('map_weight')} reason={analysis.get('risk_reason')} "
            f"front={stored_event['front_cm']} left={stored_event['left_cm']} "
            f"right={stored_event['right_cm']} down={stored_event['down_cm']}",
            flush=True,
        )

    if lite:
        return {
            "accepted": True,
            "device_id": frame.device_id,
            "risk_type": analysis["risk_type"],
            "risk_level": analysis["risk_level"],
            "stored_event_id": stored_event["id"] if stored_event else None,
            "device_state": device_state,
        }

    return {
        "accepted": True,
        "device_id": frame.device_id,
        "lat": lat,
        "lng": lng,
        "risk": analysis,
        "device_state": device_state,
        "stored_event": stored_event,
        "hardware": {
            "tof_mapping": HARDWARE_PROFILE["tof_sensors"],
            "imu": HARDWARE_PROFILE["built_in_sensors"]["imu"],
            "buzzer": HARDWARE_PROFILE["actuators"]["buzzer"],
            "motors": HARDWARE_PROFILE["actuators"]["vibration_motors"],
        },
    }


@app.get("/api/device-state/latest")
def latest_device_state(device_id: Optional[str] = Query(None, min_length=1)) -> dict[str, Any]:
    query = "SELECT * FROM device_state"
    params: tuple[Any, ...] = ()
    if device_id:
        query += " WHERE device_id = ?"
        params = (device_id,)
    query += " ORDER BY updated_at DESC LIMIT 1"
    with db() as conn:
        row = conn.execute(query, params).fetchone()
    if row:
        return {"success": True, "found": True, "state": device_state_to_dict(row)}

    # Fallback: make the page feel truthful even before /api/sensor-frames is used.
    query = "SELECT * FROM risk_events"
    params = ()
    if device_id:
        query += " WHERE device_id = ?"
        params = (device_id,)
    query += " ORDER BY id DESC LIMIT 1"
    with db() as conn:
        event = conn.execute(query, params).fetchone()
    if not event:
        return {"success": True, "found": False, "state": None}
    item = mobile_event_dict(event)
    return {
        "success": True,
        "found": True,
        "derived": True,
        "state": {
            "deviceId": item.get("deviceId"),
            "deviceName": item.get("deviceName") or item.get("device_id") or item.get("deviceId"),
            "device_name": item.get("deviceName") or item.get("device_id") or item.get("deviceId"),
            "updatedAt": item.get("timestamp"),
            "online": True,
            "latitude": item.get("latitude"),
            "longitude": item.get("longitude"),
            "battery": None,
            "frontCm": item.get("frontCm"),
            "leftCm": item.get("leftCm"),
            "rightCm": item.get("rightCm"),
            "downCm": item.get("downCm"),
            "riskType": item.get("riskType"),
            "riskLevel": item.get("riskLevel"),
            "riskScore": item.get("riskScore") or 0,
            "voicePrompt": item.get("voicePrompt") or item.get("message"),
            "source": "latest_event",
        },
    }


@app.get("/api/device-state")
def list_device_states(limit: int = Query(20, ge=1, le=100)) -> dict[str, Any]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM device_state ORDER BY updated_at DESC LIMIT ?", (limit,)).fetchall()
    states = [device_state_to_dict(row) for row in rows]
    return {"success": True, "devices": states, "onlineCount": sum(1 for item in states if item["online"])}


@app.get("/api/collaboration/overview")
def collaboration_overview(limit: int = Query(20, ge=1, le=100)) -> dict[str, Any]:
    points = active_risk_points(limit=limit)
    states = list_device_states(limit=limit)["devices"]
    return {
        "success": True,
        "deviceCount": len(states),
        "onlineCount": sum(1 for item in states if item["online"]),
        "riskPointCount": len(points),
        "highRiskCount": sum(1 for item in points if item.get("riskLevel") == "high"),
        "mediumRiskCount": sum(1 for item in points if item.get("riskLevel") == "medium"),
        "points": points,
        "devices": states,
    }


@app.get("/api/map/status")
def amap_status() -> dict[str, Any]:
    return {
        "provider": "amap_web_service",
        "configured": amap_configured(),
        "key_env": "AMAP_WEB_KEY",
        "key_visible_to_frontend": False,
    }


@app.get("/api/map/regeo")
async def map_regeo(
    lat: float = Query(..., ge=-90, le=90),
    lng: float = Query(..., ge=-180, le=180),
    coordsys: str = Query("gps"),
) -> dict[str, Any]:
    return await reverse_geocode(lat, lng, coordsys)


@app.get("/api/map/geocode")
async def map_geocode(
    address: str = Query(..., min_length=1),
    city: Optional[str] = Query(None),
) -> dict[str, Any]:
    return await geocode_address(address, city)


def create_navigation_session(
    device_id: str,
    user_id: Optional[str],
    route: dict[str, Any],
    destination_text: Optional[str],
    route_preference: str = "safe",
) -> str:
    best = route.get("best_route") or {}
    origin = route.get("origin") or {}
    destination = route.get("destination") or {}
    session_id = str(uuid.uuid4())
    now = now_iso()
    with db() as conn:
        conn.execute(
            """
            INSERT INTO navigation_sessions (session_id, device_id, user_id, origin_lat, origin_lng, destination_lat, destination_lng, destination_text, route_polyline_json, route_steps_json, current_step_index, started_at, updated_at, status, last_lat, last_lng)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'active', ?, ?)
            """,
            (
                session_id,
                device_id,
                user_id,
                float(origin.get("lat") or 0),
                float(origin.get("lng") or 0),
                float(destination.get("lat") or 0),
                float(destination.get("lng") or 0),
                destination_text,
                json.dumps(best.get("polyline") or [], ensure_ascii=False),
                json.dumps(best.get("steps") or [], ensure_ascii=False),
                now,
                now,
                float(origin.get("lat") or 0),
                float(origin.get("lng") or 0),
            ),
        )
        conn.execute(
            "UPDATE navigation_sessions SET route_preference = ? WHERE session_id = ?",
            (route_preference, session_id),
        )
    return session_id


def navigation_step_index(steps: list[dict[str, Any]], lat: float, lng: float) -> tuple[int, float]:
    best_index = 0
    best_distance = float("inf")
    for idx, step in enumerate(steps):
        points = parse_polyline(str(step.get("polyline") or ""))
        if not points:
            continue
        distance = min_distance_to_polyline_m(lat, lng, points)
        if distance < best_distance:
            best_index = idx
            best_distance = distance
    return best_index, best_distance


def distance_to_step_action_m(step: Optional[dict[str, Any]], lat: float, lng: float) -> float:
    points = parse_polyline(str((step or {}).get("polyline") or ""))
    if not points:
        return float((step or {}).get("distance") or 0.0)
    endpoint = points[-1]
    return haversine_m(lat, lng, float(endpoint["lat"]), float(endpoint["lng"]))


def finish_active_traversal(
    conn: sqlite3.Connection,
    traversal_id: Optional[int],
    *,
    status: str,
    safe_pass: bool,
    risk_event_count_delta: int = 0,
    distance_delta_m: float = 0.0,
) -> Optional[int]:
    if not traversal_id:
        return None
    row = conn.execute("SELECT * FROM road_traversals WHERE id = ? AND status = 'active'", (traversal_id,)).fetchone()
    if not row:
        return None
    started = datetime.fromisoformat(str(row["started_at"]))
    duration = max(0.0, (datetime.now(timezone.utc) - started).total_seconds())
    conn.execute(
        """
        UPDATE road_traversals
        SET finished_at = ?, duration_seconds = ?, distance_m = distance_m + ?,
            risk_event_count = risk_event_count + ?, safe_pass = ?, status = ?
        WHERE id = ?
        """,
        (now_iso(), duration, max(0.0, distance_delta_m), risk_event_count_delta, 1 if safe_pass else 0, status, traversal_id),
    )
    return int(row["road_segment_id"]) if status == "completed" else None


def start_step_traversal(
    conn: sqlite3.Connection,
    *,
    device_id: str,
    session_id: str,
    step: Optional[dict[str, Any]],
) -> Optional[int]:
    segment_id = (step or {}).get("road_segment_id")
    if not segment_id:
        return None
    cur = conn.execute(
        """
        INSERT INTO road_traversals
          (road_segment_id, device_id, navigation_session_id, started_at, distance_m, safe_pass, risk_event_count, status)
        VALUES (?, ?, ?, ?, 0, 1, 0, 'active')
        """,
        (int(segment_id), device_id, session_id, now_iso()),
    )
    return int(cur.lastrowid)


@app.post("/api/navigation/sessions/{session_id}/update")
def update_navigation_session(session_id: str, update: NavigationSessionUpdate) -> dict[str, Any]:
    completed_segment_ids: list[int] = []
    with db() as conn:
        row = conn.execute("SELECT * FROM navigation_sessions WHERE session_id = ?", (session_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="navigation session not found")
        steps = json.loads(row["route_steps_json"] or "[]")
        previous_step_index = int(row["current_step_index"] or 0)
        step_index, off_route_m = navigation_step_index(steps, update.lat, update.lng)
        off_route_count = int(row["off_route_count"] or 0) + 1 if off_route_m > 25 else 0
        destination_distance_m = haversine_m(update.lat, update.lng, float(row["destination_lat"]), float(row["destination_lng"]))
        arrival_count = int(row["arrival_count"] or 0) + 1 if destination_distance_m <= 20 else 0
        arrived = arrival_count >= 3
        should_replan = off_route_count >= 3 and not arrived
        status = update.status or ("arrived" if arrived else "off_route" if should_replan else "active")
        active_traversal_id = row["active_traversal_id"]
        if active_traversal_id:
            conn.execute(
                """
                UPDATE road_traversals
                   SET distance_m = distance_m + ?,
                       risk_event_count = risk_event_count + ?,
                       safe_pass = CASE WHEN risk_event_count + ? > 0 THEN 0 ELSE safe_pass END
                 WHERE id = ? AND status = 'active'
                """,
                (
                    max(0.0, update.distance_delta_m or 0.0),
                    update.risk_event_count_delta,
                    update.risk_event_count_delta,
                    active_traversal_id,
                ),
            )
        if step_index != previous_step_index:
            traversal = conn.execute(
                "SELECT risk_event_count FROM road_traversals WHERE id = ?",
                (active_traversal_id,),
            ).fetchone() if active_traversal_id else None
            completed_segment = finish_active_traversal(
                conn,
                active_traversal_id,
                status="completed",
                safe_pass=not traversal or int(traversal["risk_event_count"] or 0) == 0,
            )
            if completed_segment:
                completed_segment_ids.append(completed_segment)
            active_traversal_id = start_step_traversal(
                conn, device_id=str(row["device_id"]), session_id=session_id,
                step=steps[step_index] if steps and step_index < len(steps) else None,
            )
        elif active_traversal_id is None:
            active_traversal_id = start_step_traversal(
                conn, device_id=str(row["device_id"]), session_id=session_id,
                step=steps[step_index] if steps and step_index < len(steps) else None,
            )
        if arrived:
            traversal = conn.execute(
                "SELECT risk_event_count FROM road_traversals WHERE id = ?",
                (active_traversal_id,),
            ).fetchone() if active_traversal_id else None
            completed_segment = finish_active_traversal(
                conn, active_traversal_id, status="completed",
                safe_pass=not traversal or int(traversal["risk_event_count"] or 0) == 0,
            )
            if completed_segment:
                completed_segment_ids.append(completed_segment)
            active_traversal_id = None
        conn.execute(
            """UPDATE navigation_sessions
               SET current_step_index = ?, updated_at = ?, status = ?, last_lat = ?, last_lng = ?,
                   off_route_count = ?, arrival_count = ?, active_traversal_id = ?
               WHERE session_id = ?""",
            (step_index, now_iso(), status, update.lat, update.lng, off_route_count, arrival_count, active_traversal_id, session_id),
        )
    for segment_id in set(completed_segment_ids):
        recalculate_road_risk_score(segment_id)
    step = steps[step_index] if steps and step_index < len(steps) else None
    distance_to_next_action_m = distance_to_step_action_m(step, update.lat, update.lng)
    return {
        "success": True, "session_id": session_id, "status": status,
        "current_step_index": step_index, "distance_to_route_m": round(off_route_m, 1),
        "distance_to_destination_m": round(destination_distance_m, 1),
        "distance_to_next_action_m": round(distance_to_next_action_m, 1),
        "off_route": should_replan, "off_route_count": off_route_count,
        "arrived": arrived, "arrival_count": arrival_count,
        "current_step": step, "should_replan": should_replan,
    }


@app.post("/api/device-commands")
def create_device_command(request: DeviceCommandCreate) -> dict[str, Any]:
    now = now_iso()
    with db() as conn:
        if request.command == "cancel_fall":
            # Keep only one pending cancellation per device.
            existing = conn.execute(
                "SELECT id FROM device_commands WHERE device_id = ? AND command = ? AND status = 'pending' LIMIT 1",
                (request.device_id, request.command),
            ).fetchone()
            if existing:
                command_id = int(existing["id"])
            else:
                cur = conn.execute(
                    "INSERT INTO device_commands (device_id, command, source, created_at, status) VALUES (?, ?, ?, ?, 'pending')",
                    (request.device_id, request.command, request.source, now),
                )
                command_id = int(cur.lastrowid)
        else:
            raise HTTPException(status_code=400, detail="unsupported command")
    return {"success": True, "command_id": command_id, "status": "pending"}


@app.get("/api/device-commands/next")
def next_device_command(device_id: str = Query(..., min_length=1)) -> dict[str, Any]:
    with db() as conn:
        row = conn.execute(
            "SELECT * FROM device_commands WHERE device_id = ? AND status = 'pending' ORDER BY id LIMIT 1",
            (device_id,),
        ).fetchone()
        if not row:
            return {"success": True, "command": None}
        delivered_at = now_iso()
        conn.execute(
            "UPDATE device_commands SET status = 'delivered', delivered_at = ? WHERE id = ?",
            (delivered_at, row["id"]),
        )
    return {
        "success": True,
        "command": {
            "id": int(row["id"]), "device_id": row["device_id"],
            "command": row["command"], "source": row["source"],
            "created_at": row["created_at"], "delivered_at": delivered_at,
        },
    }


@app.post("/api/navigation/sessions/{session_id}/stop")
def stop_navigation_session(session_id: str) -> dict[str, Any]:
    with db() as conn:
        row = conn.execute("SELECT * FROM navigation_sessions WHERE session_id = ?", (session_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="navigation session not found")
        requested_status = "completed" if str(row["status"]) == "arrived" else "cancelled"
        finish_active_traversal(conn, row["active_traversal_id"], status=requested_status, safe_pass=False)
        conn.execute(
            "UPDATE navigation_sessions SET status = ?, active_traversal_id = NULL, updated_at = ? WHERE session_id = ?",
            (requested_status, now_iso(), session_id),
        )
    return {"success": True, "session_id": session_id, "status": requested_status}


@app.post("/api/navigation/sessions/{session_id}/replan")
async def replan_navigation_session(session_id: str) -> dict[str, Any]:
    with db() as conn:
        row = conn.execute("SELECT * FROM navigation_sessions WHERE session_id = ?", (session_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="navigation session not found")
    if row["last_lat"] is None or row["last_lng"] is None:
        raise HTTPException(status_code=409, detail="navigation session has no current location")
    request = MapRouteRequest(
        device_id=str(row["device_id"]),
        origin_lat=float(row["last_lat"]),
        origin_lng=float(row["last_lng"]),
        destination_lat=float(row["destination_lat"]),
        destination_lng=float(row["destination_lng"]),
        destination_text=row["destination_text"],
        coordsys="gps",
        route_preference=str(row["route_preference"] or "safe"),
    )
    response = await risk_aware_route(request)
    best = response.get("best_route")
    if not best:
        raise HTTPException(status_code=502, detail="no walking route returned during replan")
    with db() as conn:
        current = conn.execute("SELECT active_traversal_id FROM navigation_sessions WHERE session_id = ?", (session_id,)).fetchone()
        finish_active_traversal(conn, current["active_traversal_id"] if current else None, status="incomplete", safe_pass=False)
        conn.execute(
            """
            UPDATE navigation_sessions
            SET route_polyline_json = ?, route_steps_json = ?, current_step_index = 0,
                updated_at = ?, status = 'active', off_route_count = 0, arrival_count = 0,
                active_traversal_id = NULL
            WHERE session_id = ?
            """,
            (
                json.dumps(best.get("polyline") or [], ensure_ascii=False),
                json.dumps(best.get("steps") or [], ensure_ascii=False),
                now_iso(),
                session_id,
            ),
        )
    # risk_aware_route created a temporary session; retain the original stable
    # session id used by the foreground service and cancel the temporary one.
    temporary_session_id = response.get("session_id")
    if temporary_session_id and temporary_session_id != session_id:
        with db() as conn:
            conn.execute("DELETE FROM navigation_sessions WHERE session_id = ?", (temporary_session_id,))
    response["session_id"] = session_id
    response["navigation_status"] = "replanned"
    return response

@app.get("/api/road-risk/segments")
def list_road_risk_segments(limit: int = Query(100, ge=1, le=500)) -> dict[str, Any]:
    with db() as conn:
        rows = conn.execute(
            """
            SELECT rs.*, COALESCE(score.risk_score, 0) AS risk_score,
                   COALESCE(score.confidence_score, 0) AS confidence_score,
                   score.event_density_per_km, score.unique_device_count,
                   score.safe_traversal_count, score.high_count, score.medium_count,
                   score.low_count, score.main_risk_type, score.calculated_at
            FROM road_segments rs
            LEFT JOIN road_risk_scores score ON score.road_segment_id = rs.id
            ORDER BY COALESCE(score.risk_score, 0) DESC, rs.updated_at DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    segments = []
    for row in rows:
        item = row_to_dict(row)
        try:
            item["polyline"] = json.loads(item.pop("polyline_json") or "[]")
        except Exception:
            item["polyline"] = []
        segments.append(item)
    return {"success": True, "segments": segments}



@app.post("/api/navigation/risk-aware-route")
async def risk_aware_route(request: MapRouteRequest) -> dict[str, Any]:
    origin_lat, origin_lng, dest_lat, dest_lng, resolved = await resolve_route_endpoint(request)
    origin_coordsys = str(resolved.get("origin", {}).get("coordsys") or request.coordsys)
    dest_coordsys = str(resolved.get("destination", {}).get("coordsys") or request.coordsys)
    origin_amap_lat, origin_amap_lng = await convert_to_amap_coord(origin_lat, origin_lng, origin_coordsys)
    dest_amap_lat, dest_amap_lng = await convert_to_amap_coord(dest_lat, dest_lng, dest_coordsys)
    straight_distance_m = haversine_m(origin_amap_lat, origin_amap_lng, dest_amap_lat, dest_amap_lng)
    if straight_distance_m > WALKING_NAVIGATION_MAX_DISTANCE_M:
        voice_prompt = "目的地超过3公里，当前仅支持3公里内的步行导航。请换一个更近的目的地。"
        return {
            "routes": [],
            "best_route": None,
            "route_count": 0,
            "provider": "amap_web_service",
            "navigation_mode": "walking",
            "navigation_status": "out_of_walking_range",
            "walking_max_distance_m": WALKING_NAVIGATION_MAX_DISTANCE_M,
            "straight_distance_m": round(straight_distance_m, 1),
            "resolved": resolved,
            "origin": {"lat": origin_lat, "lng": origin_lng, "coordsys": origin_coordsys},
            "destination": {"lat": dest_lat, "lng": dest_lng, "coordsys": dest_coordsys},
            "amap_origin": {"lat": origin_amap_lat, "lng": origin_amap_lng},
            "amap_destination": {"lat": dest_amap_lat, "lng": dest_amap_lng},
            "voice_prompt": voice_prompt,
        }
    route = await plan_walking_route(origin_lat, origin_lng, dest_lat, dest_lng, origin_coordsys, dest_coordsys)
    enriched = await enrich_walking_route(route, request.route_buffer_m)
    if request.route_preference == "distance" and enriched.get("shortest_route"):
        enriched["best_route"] = enriched["shortest_route"]
        enriched["selected_route_index"] = enriched["shortest_route"].get("index")
    sensor_analysis = None
    if request.sensor_frame:
        history = nearby_summary(origin_lat, origin_lng, request.risk_radius_m)
        sensor_analysis = analyze_sensor_frame(request.sensor_frame, history)
    llm_advice = await generate_route_advice(enriched.get("best_route"), sensor_analysis)
    response = {
        **enriched,
        "provider": "amap_web_service",
        "navigation_mode": "walking",
        "navigation_status": "ready",
        "walking_max_distance_m": WALKING_NAVIGATION_MAX_DISTANCE_M,
        "straight_distance_m": round(straight_distance_m, 1),
        "resolved": resolved,
        "origin": route["input"]["origin"],
        "destination": route["input"]["destination"],
        "amap_origin": route["amap_origin"],
        "amap_destination": route["amap_destination"],
        "risk_method": {
            "name": "active_risk_points_route_buffer_v2",
            "description": "active risk points within the route polyline buffer are counted; lower combined score is safer",
            "route_buffer_m": request.route_buffer_m,
            "counted_levels": ["medium", "high"],
        },
        "sensor_analysis": sensor_analysis,
        "llm_advice": llm_advice,
        "voice_prompt": enriched.get("voice_prompt") or llm_advice.get("advice"),
    }
    selected = enriched.get("best_route") or {}
    selected_risk = selected.get("risk") or {}
    response.update(
        {
            "selected_route_index": enriched.get("selected_route_index"),
            "alternative_routes": enriched.get("routes") or [],
            "route_polyline": selected.get("polyline") or [],
            "route_steps": selected.get("steps") or [],
            "matched_risk_points": selected_risk.get("risk_points") or [],
            "distance_m": selected.get("distance_m"),
            "duration_seconds": selected.get("duration_s"),
            "high_risk_count": selected_risk.get("high_count", 0),
            "medium_risk_count": selected_risk.get("medium_count", 0),
            "low_risk_count": selected_risk.get("low_count", 0),
            "risk_point_count": selected_risk.get("risk_count", 0),
            "weighted_risk_score": selected_risk.get("risk_score", 0),
            "road_risk_score": selected.get("risk_score", 0),
        }
    )
    if enriched.get("best_route"):
        response["session_id"] = create_navigation_session(
            request.device_id, None, response, request.destination_text, request.route_preference
        )
    return response


@app.post("/api/navigation/voice-route")
async def voice_route(request: VoiceRouteRequest) -> dict[str, Any]:
    parsed_command = await parse_route_text_with_llm(request.text)
    origin_text = parsed_command.get("origin_text")
    destination_text = parsed_command.get("destination_text")
    if parsed_command.get("intent") in {"repeat", "query_risk", "sos", "unknown"} and not destination_text:
        return {
            "text": request.text,
            "parsed": parsed_command,
            "voice_prompt": parsed_command.get("reply") or "\u5df2\u6536\u5230\u8bed\u97f3\u6307\u4ee4",
            "route_count": 0,
            "best_route": None,
            "provider": parsed_command.get("provider"),
            "model": parsed_command.get("model"),
        }
    route_request = MapRouteRequest(
        device_id=request.device_id,
        origin_lat=request.current_lat,
        origin_lng=request.current_lng,
        origin_text=origin_text,
        destination_text=destination_text,
        city=request.city,
        coordsys=request.coordsys,
        route_preference=request.route_preference,
    )
    route = await risk_aware_route(route_request)
    return {
        "text": request.text,
        "parsed": parsed_command,
        **route,
    }


@app.get("/api/map/risk-points")
def map_risk_points(
    lat: Optional[float] = Query(None, ge=-90, le=90),
    lng: Optional[float] = Query(None, ge=-180, le=180),
    radius: float = Query(500.0, gt=0, le=10000),
    limit: int = Query(200, ge=1, le=1000),
) -> dict[str, Any]:
    points = active_risk_points(lat, lng, radius if lat is not None and lng is not None else None, limit)
    if points:
        return {
            "risk_count": len(points),
            "high_count": sum(1 for item in points if item["riskLevel"] == "high"),
            "medium_count": sum(1 for item in points if item["riskLevel"] == "medium"),
            "max_level": max((item["riskLevel"] for item in points), key=lambda level: LEVEL_RANK[level], default="low"),
            "clustered": True,
            "cluster_radius_m": RISK_POINT_CLUSTER_RADIUS_M,
            "points": points,
        }

    # Compatibility fallback for existing databases that only have raw events.
    if lat is not None and lng is not None:
        summary = nearby_summary(lat, lng, radius)
        return {
            "risk_count": summary["risk_count"],
            "high_count": summary["high_count"],
            "medium_count": summary["medium_count"],
            "max_level": summary["max_level"],
            "clustered": False,
            "points": summary["recent_events"][:limit],
        }
    events = list_events(limit)
    return {
        "risk_count": len(events),
        "high_count": sum(1 for item in events if item["risk_level"] == "high"),
        "medium_count": sum(1 for item in events if item["risk_level"] == "medium"),
        "max_level": max((item["risk_level"] for item in events), key=lambda level: LEVEL_RANK[level], default="low"),
        "clustered": False,
        "points": events,
    }


@app.get("/status")
def legacy_status() -> dict[str, Any]:
    devices = legacy_device_list()
    return {
        "online": True,
        "message": "\u540e\u7aef\u5df2\u8fde\u63a5\uff0c\u6b63\u5728\u8bb0\u5f55\u8def\u7ebf\u548c\u98ce\u9669\u70b9",
        "deviceCount": len(devices),
    }


@app.get("/devices")
def legacy_devices() -> dict[str, Any]:
    return {"devices": legacy_device_list()}


@app.get("/events/latest")
def legacy_latest_events(limit: int = Query(50, ge=1, le=200)) -> dict[str, Any]:
    with db() as conn:
        rows = conn.execute("SELECT * FROM risk_events ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
    return {"events": [mobile_event_dict(row) for row in rows]}


@app.get("/api/alerts/latest")
def latest_alerts(
    role: str = Query("blind", pattern="^(blind|companion)$"),
    userId: Optional[str] = Query(None),
    deviceId: Optional[str] = Query(None),
    sinceId: int = Query(0, ge=0),
    limit: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    devices = allowed_alert_devices(role, userId, deviceId)
    placeholders = ",".join("?" for _ in ALERT_RISK_TYPES)
    clauses = [f"risk_type IN ({placeholders})", "id > ?"]
    params: list[Any] = list(ALERT_RISK_TYPES) + [sinceId]
    if devices is not None:
        device_placeholders = ",".join("?" for _ in devices)
        clauses.append(f"device_id IN ({device_placeholders})")
        params.extend(sorted(devices))

    where = " AND ".join(clauses)
    with db() as conn:
        rows = conn.execute(
            f"SELECT * FROM risk_events WHERE {where} ORDER BY id DESC LIMIT ?",
            tuple(params + [limit]),
        ).fetchall()

    alerts = [
        alert_event_payload(row, role)
        for row in rows
        if role in alert_target_roles(str(row["risk_type"]))
    ]

    return {
        "success": True,
        "role": role,
        "alerts": alerts,
        "devices": sorted(devices) if devices is not None else None,
    }


@app.post("/sos", status_code=201)
def legacy_sos(request: LegacySosCreate) -> dict[str, Any]:
    lat, lng = resolve_legacy_location(request.deviceId, request.latitude, request.longitude)
    store_legacy_location(request.deviceId, request.latitude, request.longitude)
    stored = store_event(
        EventCreate(
            device_id=request.deviceId,
            lat=lat,
            lng=lng,
            risk_type="sos",
            risk_level="high",
            level="high",
            direction="stop",
            sensor="android_app",
            distance_mm=None,
            battery=None,
            front_cm=None,
            left_cm=None,
            right_cm=None,
            down_cm=None,
            extra_json={"message": request.message, "source": "android_app_sos"},
            timestamp=now_iso(),
        )
    )
    return {
        "success": True,
        "message": "\u7d27\u6025\u6c42\u52a9\u5df2\u8bb0\u5f55",
        "sos": {
            "id": stored["id"],
            "deviceId": stored["device_id"],
            "latitude": stored["lat"],
            "longitude": stored["lng"],
            "message": request.message,
            "receivedAt": stored["timestamp"],
        },
    }


@app.post("/pairing-codes", status_code=201)
def create_pairing_code(request: PairingCodeCreate) -> dict[str, Any]:
    created_at = now_iso()
    expires_at = (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(timespec="seconds")
    blind_name = user_display_name(request.blindUserId, "blind")
    device_name = device_display_name(request.deviceId)

    with db() as conn:
        conn.execute(
            "UPDATE pairing_codes SET status = 'expired' WHERE blind_user_id = ? AND device_id = ? AND status = 'active'",
            (request.blindUserId, request.deviceId),
        )
        code = ""
        for _ in range(50):
            candidate = f"{secrets.randbelow(900000) + 100000}"
            existing = conn.execute(
                "SELECT code FROM pairing_codes WHERE code = ? AND status = 'active'",
                (candidate,),
            ).fetchone()
            if not existing:
                code = candidate
                break
        if not code:
            raise HTTPException(status_code=503, detail="\u914d\u5bf9\u7801\u751f\u6210\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5")

        conn.execute(
            """
            INSERT INTO pairing_codes
                (code, blind_user_id, blind_name, device_id, device_name, created_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'active')
            """,
            (code, request.blindUserId, blind_name, request.deviceId, device_name, created_at, expires_at),
        )
        row = conn.execute("SELECT * FROM pairing_codes WHERE code = ?", (code,)).fetchone()

    return pairing_code_payload(row)


@app.get("/pairing-codes/{code}")
def get_pairing_code(code: str) -> dict[str, Any]:
    return pairing_code_payload(fetch_pairing_code(code.strip()))


@app.post("/care-relations/requests", status_code=201)
def create_care_relation_request(request: CareRelationRequestCreate) -> dict[str, Any]:
    pairing = fetch_pairing_code(request.code.strip())
    request_id = f"request_{uuid.uuid4().hex[:12]}"
    created_at = now_iso()
    companion_name = request.companionName.strip() or user_display_name(request.companionUserId, "companion")

    with db() as conn:
        conn.execute(
            """
            INSERT INTO care_requests
                (request_id, code, status, blind_user_id, blind_name, companion_user_id,
                 companion_name, device_id, device_name, created_at, updated_at)
            VALUES (?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                request_id,
                pairing["code"],
                pairing["blind_user_id"],
                pairing["blind_name"],
                request.companionUserId,
                companion_name,
                pairing["device_id"],
                pairing["device_name"],
                created_at,
                created_at,
            ),
        )

    return {"success": True, "requestId": request_id, "status": "pending"}


@app.get("/care-relations/requests")
def get_care_relation_requests(
    blindUserId: Optional[str] = Query(None),
    companionUserId: Optional[str] = Query(None),
) -> dict[str, Any]:
    clauses: list[str] = []
    params: list[str] = []
    if blindUserId:
        clauses.append("blind_user_id = ?")
        params.append(blindUserId)
    if companionUserId:
        clauses.append("companion_user_id = ?")
        params.append(companionUserId)
    where = "WHERE " + " AND ".join(clauses) if clauses else ""
    with db() as conn:
        rows = conn.execute(
            f"SELECT * FROM care_requests {where} ORDER BY updated_at DESC LIMIT 100",
            tuple(params),
        ).fetchall()
    return {"success": True, "requests": [care_request_payload(row) for row in rows]}


@app.post("/care-relations/{request_id}/approve")
def approve_care_relation_request(request_id: str) -> dict[str, Any]:
    updated_at = now_iso()
    with db() as conn:
        request_row = conn.execute("SELECT * FROM care_requests WHERE request_id = ?", (request_id,)).fetchone()
        if not request_row:
            raise HTTPException(status_code=404, detail="\u672a\u627e\u5230\u8be5\u966a\u62a4\u7533\u8bf7")

        existing_relation = conn.execute(
            """
            SELECT * FROM care_relations
            WHERE blind_user_id = ? AND companion_user_id = ? AND device_id = ?
            ORDER BY updated_at DESC
            LIMIT 1
            """,
            (request_row["blind_user_id"], request_row["companion_user_id"], request_row["device_id"]),
        ).fetchone()

        if existing_relation:
            relation_id = existing_relation["relation_id"]
            conn.execute(
                "UPDATE care_relations SET status = 'active', updated_at = ? WHERE relation_id = ?",
                (updated_at, relation_id),
            )
        else:
            relation_id = f"relation_{uuid.uuid4().hex[:12]}"
            conn.execute(
                """
                INSERT INTO care_relations
                    (relation_id, status, blind_user_id, blind_name, companion_user_id,
                     companion_name, device_id, device_name, created_at, updated_at)
                VALUES (?, 'active', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    relation_id,
                    request_row["blind_user_id"],
                    request_row["blind_name"],
                    request_row["companion_user_id"],
                    request_row["companion_name"],
                    request_row["device_id"],
                    request_row["device_name"],
                    updated_at,
                    updated_at,
                ),
            )

        conn.execute(
            "UPDATE care_requests SET status = 'active', updated_at = ? WHERE request_id = ?",
            (updated_at, request_id),
        )
        relation = conn.execute("SELECT * FROM care_relations WHERE relation_id = ?", (relation_id,)).fetchone()

    return {
        "success": True,
        "requestId": request_id,
        "status": "active",
        "relation": care_relation_payload(relation),
    }


@app.post("/care-relations/{request_id}/reject")
def reject_care_relation_request(request_id: str) -> dict[str, Any]:
    with db() as conn:
        row = conn.execute("SELECT * FROM care_requests WHERE request_id = ?", (request_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="\u672a\u627e\u5230\u8be5\u966a\u62a4\u7533\u8bf7")
        conn.execute(
            "UPDATE care_requests SET status = 'rejected', updated_at = ? WHERE request_id = ?",
            (now_iso(), request_id),
        )
    return {"success": True, "requestId": request_id, "status": "rejected", "relation": None}


@app.get("/care-relations")
def get_care_relations(userId: str = Query(..., min_length=1), role: str = Query("blind")) -> dict[str, Any]:
    field = "companion_user_id" if role == "companion" else "blind_user_id"
    with db() as conn:
        rows = conn.execute(
            f"SELECT * FROM care_relations WHERE {field} = ? AND status = 'active' ORDER BY updated_at DESC",
            (userId,),
        ).fetchall()
    relations = [care_relation_payload(row) for row in rows]
    return {"success": True, "relations": relations, "relation": relations[0] if relations else None}


@app.delete("/care-relations/{relation_id}")
def remove_care_relation(relation_id: str) -> dict[str, Any]:
    with db() as conn:
        conn.execute(
            "UPDATE care_relations SET status = 'removed', updated_at = ? WHERE relation_id = ?",
            (now_iso(), relation_id),
        )
    return {"success": True, "relationId": relation_id, "status": "removed"}


@app.post("/telemetry", status_code=201)
def legacy_telemetry(request: LegacyTelemetryCreate) -> dict[str, Any]:
    sensor_result = create_sensor_frame(
        SensorFrameCreate(
            device_id=request.deviceId,
            lat=request.latitude,
            lng=request.longitude,
            front_cm=int(request.frontDistanceMm / 10) if request.frontDistanceMm is not None else None,
            left_cm=int(request.leftDistanceMm / 10) if request.leftDistanceMm is not None else None,
            right_cm=int(request.rightDistanceMm / 10) if request.rightDistanceMm is not None else None,
            down_cm=int(request.downDistanceMm / 10) if request.downDistanceMm is not None else None,
            battery=request.battery,
            source="android_telemetry",
            timestamp=request.timestamp or now_iso(),
        )
    )
    generated = [sensor_result["stored_event"]] if sensor_result.get("stored_event") else []

    return {
        "success": True,
        "message": "\u9065\u6d4b\u5df2\u63a5\u6536",
        "generatedEvents": len(generated),
        "risk": sensor_result["risk"],
        "events": [
            {
                "id": event["id"],
                "deviceId": event["device_id"],
                "riskType": event["risk_type"],
                "riskLevel": event["risk_level"],
                "distance": event.get("distance_mm"),
                "message": legacy_event_message(event),
                "voicePrompt": event.get("voice_prompt") or legacy_event_message(event),
                "riskScore": event.get("risk_score"),
                "feedback": json.loads(event["feedback_json"]) if event.get("feedback_json") else None,
                "latitude": event.get("lat"),
                "longitude": event.get("lng"),
                "timestamp": event.get("timestamp"),
            }
            for event in generated
        ],
    }


@app.get("/api/locations/latest")
def latest_location(device_id: str = Query(..., min_length=1)) -> Optional[dict[str, Any]]:
    with db() as conn:
        row = conn.execute(
            "SELECT * FROM device_locations WHERE device_id = ? ORDER BY id DESC LIMIT 1",
            (device_id,),
        ).fetchone()
    return row_to_dict(row) if row else None


@app.get("/api/locations/history")
def location_history(
    device_id: str = Query(..., min_length=1),
    limit: int = Query(200, ge=1, le=1000),
) -> list[dict[str, Any]]:
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM device_locations WHERE device_id = ? ORDER BY id DESC LIMIT ?",
            (device_id, limit),
        ).fetchall()
    return [row_to_dict(row) for row in rows]


@app.get("/api/risks/nearby")
def nearby_risks(
    lat: float = Query(...),
    lng: float = Query(...),
    radius: float = Query(80.0, gt=0, le=5000),
) -> dict[str, Any]:
    return nearby_summary(lat, lng, radius)


def nearby_warning_text(distance_m: float, risk_level: str, direction: str, event: dict[str, Any]) -> str:
    level_text = {"high": "\u9ad8", "medium": "\u4e2d", "low": "\u4f4e"}.get(risk_level, "\u9ad8")
    direction_text = relative_direction_label(direction)
    base = f"{direction_text}\u7ea6 {max(1, int(round(distance_m)))} \u7c73\u6709{level_text}\u98ce\u9669\uff0c\u8bf7\u6ce8\u610f\u907f\u8ba9\u3002"
    detail = str(event.get("voicePrompt") or event.get("message") or "").strip()
    if detail and detail not in base:
        return f"{base}{detail}"
    return base

@app.get("/api/risks/nearby-warning")
def nearby_risk_warning(
    lat: float = Query(..., ge=-90, le=90),
    lng: float = Query(..., ge=-180, le=180),
    radius: float = Query(50.0, gt=0, le=5000),
    min_level: str = Query("medium", pattern="^(low|medium|high)$"),
    bearing_deg: Optional[float] = Query(None, ge=0, lt=360),
    fov_deg: float = Query(140.0, gt=10, le=360),
) -> dict[str, Any]:
    """Return the highest-priority nearby risk point for blind-mode voice warning.

    Risk points are clustered from all canes. When bearing_deg is supplied, only
    points inside the user's forward field of view are considered.
    """
    min_rank = LEVEL_RANK[min_level]
    candidates: list[tuple[int, float, float, int, dict[str, Any], str, Optional[float]]] = []
    for event in active_risk_points(lat, lng, radius, limit=500):
        level = str(event.get("riskLevel") or "low")
        rank = LEVEL_RANK.get(level, 0)
        if rank < min_rank:
            continue
        event_lat = float(event["latitude"])
        event_lng = float(event["longitude"])
        distance_m = haversine_m(lat, lng, event_lat, event_lng)
        delta = None
        direction = "front"
        if bearing_deg is not None:
            target_bearing = bearing_between_deg(lat, lng, event_lat, event_lng)
            delta = angle_delta_deg(target_bearing, bearing_deg)
            if abs(delta) > fov_deg / 2.0:
                continue
            direction = relative_direction(delta)
        confidence = float(event.get("confidence") or 0.0)
        event_id = int(event.get("id") or 0)
        candidates.append((rank, confidence, -distance_m, event_id, event, direction, delta))

    if not candidates:
        return {
            "success": True,
            "found": False,
            "radius_m": radius,
            "min_level": min_level,
            "bearing_deg": bearing_deg,
            "fov_deg": fov_deg,
            "warning": None,
        }

    candidates.sort(reverse=True)
    rank, confidence, neg_distance_m, event_id, event, direction, delta = candidates[0]
    distance_m = -neg_distance_m
    level = str(event.get("riskLevel") or "low")
    prompt = nearby_warning_text(distance_m, level, direction, event)
    return {
        "success": True,
        "found": True,
        "radius_m": radius,
        "min_level": min_level,
        "bearing_deg": bearing_deg,
        "fov_deg": fov_deg,
        "warning": {
            "eventId": event_id,
            "id": event_id,
            "deviceId": event.get("deviceId"),
            "riskType": event.get("riskType"),
            "riskLevel": level,
            "distanceM": round(distance_m, 1),
            "relativeDirection": direction,
            "relativeDirectionText": relative_direction_label(direction),
            "bearingDeltaDeg": round(delta, 1) if delta is not None else None,
            "confidence": confidence,
            "reportCount": event.get("reportCount"),
            "sourceDevices": event.get("sourceDevices"),
            "message": event.get("message"),
            "voicePrompt": prompt,
            "latitude": event.get("latitude"),
            "longitude": event.get("longitude"),
            "timestamp": event.get("timestamp"),
        },
    }


@app.post("/api/ai/deep-risk")
def deep_risk(req: DeepRiskRequest) -> dict[str, Any]:
    history = nearby_summary(req.lat, req.lng, req.nearby_radius_m)
    deep = score_deep_risk(req, history)
    return {
        "device_id": req.device_id,
        "lat": req.lat,
        "lng": req.lng,
        "deep_learning": deep,
        "nearby": history,
    }


@app.post("/api/ai/advice")
async def ai_advice(req: AdviceRequest) -> dict[str, Any]:
    history = nearby_summary(req.lat, req.lng, req.nearby_radius_m)
    deep = score_deep_risk(req, history)
    result = await generate_advice(req, history, deep)
    advice = result.get("advice") or fallback_advice(req, history)
    return {**result, "advice": advice, "ai_message": advice, "nearby": history, "deep_learning": deep}


@app.post("/api/ai-advice")
async def ai_advice_compat(req: AiAdviceCompatRequest) -> dict[str, Any]:
    return await ai_advice(req.to_advice_request())


LOCAL_WHISPER_MODEL: Any = None
LOCAL_WHISPER_MODEL_NAME = ""


def local_stt_requested() -> bool:
    provider = env("STT_PROVIDER", "openai").lower()
    return provider in {"local", "whisper", "faster-whisper", "faster_whisper"} or bool(env("LOCAL_STT_MODEL") or env("FASTER_WHISPER_MODEL"))


def transcribe_with_local_whisper(content: bytes, filename: str | None, language: Optional[str]) -> dict[str, Any]:
    """Transcribe with faster-whisper when cloud STT is not configured or STT_PROVIDER=local.

    Install with: pip install faster-whisper
    Configure optional env: LOCAL_STT_MODEL=base, LOCAL_STT_DEVICE=cpu, LOCAL_STT_COMPUTE_TYPE=int8.
    """
    global LOCAL_WHISPER_MODEL, LOCAL_WHISPER_MODEL_NAME
    try:
        from faster_whisper import WhisperModel
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=(
                "speech recognition is not configured: set OPENAI_API_KEY/ARK_STT_MODEL, "
                "or install faster-whisper and set STT_PROVIDER=local"
            ),
        ) from exc

    model_name = env("LOCAL_STT_MODEL", env("FASTER_WHISPER_MODEL", "base"))
    device = env("LOCAL_STT_DEVICE", "cpu")
    compute_type = env("LOCAL_STT_COMPUTE_TYPE", "int8")
    if LOCAL_WHISPER_MODEL is None or LOCAL_WHISPER_MODEL_NAME != model_name:
        LOCAL_WHISPER_MODEL = WhisperModel(model_name, device=device, compute_type=compute_type)
        LOCAL_WHISPER_MODEL_NAME = model_name

    suffix = Path(filename or "voice.m4a").suffix or ".m4a"
    temp_path = ""
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp.write(content)
            temp_path = tmp.name
        segments, info = LOCAL_WHISPER_MODEL.transcribe(
            temp_path,
            language=(language or "zh").split("-")[0],
            vad_filter=True,
        )
        text = "".join(segment.text for segment in segments).strip()
        return {
            "provider": "local_faster_whisper",
            "model": model_name,
            "text": text,
            "raw": {"language": getattr(info, "language", language or "zh")},
        }
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass


@app.post("/api/voice/text-command")
async def text_command(req: TextCommandRequest) -> dict[str, Any]:
    result = await parse_command_with_llm(req.text, req.device_id)
    return {"text": req.text, **result}


def pcm16_mono_16k_to_wav_bytes(content: bytes) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(content)
    return output.getvalue()


def normalize_audio_for_seedasr(content: bytes, filename: Optional[str], content_type: Optional[str]) -> tuple[bytes, str]:
    name = (filename or "").lower()
    mime = (content_type or "").lower()
    if name.endswith(".wav") or "wav" in mime:
        return content, "wav"
    if name.endswith(".mp3") or "mpeg" in mime or "mp3" in mime:
        return content, "mp3"
    if name.endswith(".ogg") or "ogg" in mime:
        return content, "ogg"
    if name.endswith(".m4a") or "m4a" in mime or "mp4" in mime:
        return content, "m4a"
    return pcm16_mono_16k_to_wav_bytes(content), "wav"


def seedasr_headers(cfg: dict[str, str], request_id: str) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "X-Api-Resource-Id": cfg["resource_id"],
        "X-Api-Request-Id": request_id,
        "X-Api-Sequence": "-1",
    }
    if cfg.get("api_key"):
        headers["x-api-key"] = cfg["api_key"]
        return headers
    if cfg.get("app_key") and cfg.get("access_key"):
        headers["X-Api-App-Key"] = cfg["app_key"]
        headers["X-Api-Access-Key"] = cfg["access_key"]
        return headers
    raise HTTPException(status_code=503, detail="Doubao speech recognition is not configured")


def extract_seedasr_text(payload: dict[str, Any]) -> str:
    result = payload.get("result")
    if isinstance(result, dict):
        text = str(result.get("text") or "").strip()
        if text:
            return text
        utterances = result.get("utterances")
        if isinstance(utterances, list):
            parts = [str(item.get("text") or "").strip() for item in utterances if isinstance(item, dict)]
            return "".join(part for part in parts if part)
    return str(payload.get("text") or "").strip()


async def transcribe_with_seedasr(
    cfg: dict[str, str],
    content: bytes,
    filename: Optional[str],
    content_type: Optional[str],
    uid: str,
) -> dict[str, Any]:
    audio_bytes, audio_format = normalize_audio_for_seedasr(content, filename, content_type)
    request_id = str(uuid.uuid4())
    headers = seedasr_headers(cfg, request_id)
    audio_payload = {
        "data": base64.b64encode(audio_bytes).decode("ascii"),
        "format": audio_format,
    }
    body = {
        "user": {"uid": uid or "smartcane"},
        "audio": audio_payload,
        "request": {
            "model_name": cfg["model"] or "bigmodel",
            "enable_itn": True,
            "enable_punc": True,
            "enable_ddc": False,
            "enable_speaker_info": False,
            "enable_channel_split": False,
            "show_utterances": False,
            "vad_segment": False,
            "sensitive_words_filter": "",
        },
    }
    timeout = httpx.Timeout(90.0, connect=15.0)
    async with httpx.AsyncClient(timeout=timeout) as client:
        submit_response = await client.post(cfg["submit_url"], headers=headers, json=body)
        if submit_response.status_code >= 400:
            raise HTTPException(status_code=submit_response.status_code, detail=submit_response.text)
        status_code = submit_response.headers.get("X-Api-Status-Code")
        if status_code and status_code != "20000000":
            raise HTTPException(status_code=502, detail=submit_response.headers.get("X-Api-Message", "SeedASR submit failed"))

        interval = max(0.5, float(cfg.get("poll_interval_sec") or "1.0"))
        max_polls = max(1, int(cfg.get("max_polls") or "20"))
        for _ in range(max_polls):
            await asyncio.sleep(interval)
            query_response = await client.post(cfg["query_url"], headers=headers, content="{}")
            if query_response.status_code >= 400:
                raise HTTPException(status_code=query_response.status_code, detail=query_response.text)
            query_text = query_response.text.strip()
            if not query_text or query_text == "{}":
                continue
            payload = query_response.json()
            transcript = extract_seedasr_text(payload)
            if transcript:
                return {
                    "provider": cfg["provider"],
                    "model": cfg["model"],
                    "text": transcript,
                    "request_id": request_id,
                    "raw": payload,
                }
    raise HTTPException(status_code=504, detail="Doubao speech recognition timed out")


async def transcribe_upload(
    file: UploadFile,
    language: Optional[str],
    prompt: Optional[str],
    uid: str = "smartcane",
) -> dict[str, Any]:
    cfg = stt_config()
    content = await file.read()

    if cfg["provider"] == "volc_seedasr":
        return await transcribe_with_seedasr(cfg, content, file.filename, file.content_type, uid)

    if not cfg["api_key"] or not cfg["model"]:
        return await asyncio.to_thread(transcribe_with_local_whisper, content, file.filename, language)

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

    if response.status_code >= 400 and local_stt_requested():
        return await asyncio.to_thread(transcribe_with_local_whisper, content, file.filename, language)
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)
    payload = response.json()
    return {
        "provider": cfg["provider"],
        "model": cfg["model"],
        "text": payload.get("text", ""),
        "raw": payload,
    }


def voice_route_failure_prompt(detail: Any) -> str:
    if isinstance(detail, dict):
        infocode = str(detail.get("infocode") or "")
        info = str(detail.get("info") or "")
        if infocode == "20803" or info == "OVER_DIRECTION_RANGE":
            return "语音已识别，但步行路线距离过长。请确认当前定位，或说一个更近的目的地。"
        if infocode == "20800":
            return "语音已识别，但起点或终点不在高德步行规划范围内。请换一个附近地点。"
        if infocode == "20801":
            return "语音已识别，但起点或终点附近没有可规划道路。请换一个更明确的位置。"
        if infocode == "10001":
            return "语音已识别，但高德服务 Key 不正确或已过期。"
        if infocode == "10002":
            return "语音已识别，但高德 Web 服务没有开通对应接口权限。"
        return f"语音已识别，但高德路线规划失败：{info or infocode or '未知错误'}。"
    text = str(detail or "").strip()
    if "destination coordinate" in text or "destination_text" in text:
        return "语音已识别，但没有听清目的地。请再说一次要去哪里。"
    if "origin coordinate" in text:
        return "语音已识别，但当前定位不可用。请开启定位后再试。"
    return "语音已识别，但路线规划暂时失败。请稍后再试。"


@app.post("/api/voice/transcribe")
async def transcribe_voice(
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
    prompt: Optional[str] = Form(None),
) -> dict[str, Any]:
    return await transcribe_upload(file=file, language=language, prompt=prompt)


@app.post("/api/voice/command")
async def voice_command(
    device_id: str = Form(...),
    file: UploadFile = File(...),
    language: Optional[str] = Form(None),
    current_lat: Optional[float] = Form(None),
    current_lng: Optional[float] = Form(None),
    city: Optional[str] = Form(None),
    coordsys: str = Form("gps"),
) -> dict[str, Any]:
    transcript = await transcribe_upload(file=file, language=language, prompt=None, uid=device_id)
    transcript_text = str(transcript.get("text") or "").strip()
    if not transcript_text:
        return {
            "device_id": device_id,
            "transcript": "",
            "stt": {
                "provider": transcript.get("provider"),
                "model": transcript.get("model"),
            },
            "intent": "none",
            "action": "none",
            "confidence": 0.0,
            "reply": "没有听清，请再按住说一次",
            "voice_prompt": "没有听清，请再按住说一次",
            "route_count": 0,
            "fallback": True,
        }
    try:
        route_result = await voice_route(
            VoiceRouteRequest(
                device_id=device_id,
                text=transcript_text,
                current_lat=current_lat,
                current_lng=current_lng,
                city=city,
                coordsys=coordsys,
            )
        )
    except HTTPException as exc:
        prompt_text = voice_route_failure_prompt(exc.detail)
        return {
            "device_id": device_id,
            "transcript": transcript_text,
            "stt": {
                "provider": transcript.get("provider"),
                "model": transcript.get("model"),
            },
            "reply": prompt_text,
            "voice_prompt": prompt_text,
            "route_count": 0,
            "best_route": None,
            "route_error": exc.detail,
        }
    return {
        "device_id": device_id,
        "transcript": transcript_text,
        "stt": {
            "provider": transcript.get("provider"),
            "model": transcript.get("model"),
        },
        **route_result,
    }



if __name__ == "__main__":
    import uvicorn

    init_db()
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=False)
