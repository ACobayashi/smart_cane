-- Smart Cane full-chain navigation and road-risk migration.
-- Safe to run repeatedly on SQLite. Existing compatibility tables are untouched.

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
    last_lng REAL,
    off_route_count INTEGER NOT NULL DEFAULT 0,
    arrival_count INTEGER NOT NULL DEFAULT 0,
    active_traversal_id INTEGER,
    route_preference TEXT NOT NULL DEFAULT 'safe',
    destination_coordsys TEXT NOT NULL DEFAULT 'gps'
);

CREATE TABLE IF NOT EXISTS road_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    road_name TEXT NOT NULL,
    road_segment_key TEXT,
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
);

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
    match_status TEXT NOT NULL DEFAULT 'pending',
    matched_distance_m REAL
);

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
);

CREATE TABLE IF NOT EXISTS device_commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    command TEXT NOT NULL,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    delivered_at TEXT,
    status TEXT NOT NULL DEFAULT 'pending'
);

CREATE TABLE IF NOT EXISTS device_alert_suppression (
    device_id TEXT PRIMARY KEY,
    fall_event_id TEXT,
    until_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

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
);

CREATE INDEX IF NOT EXISTS idx_navigation_sessions_device ON navigation_sessions(device_id, status);
CREATE INDEX IF NOT EXISTS idx_road_segments_name ON road_segments(road_name);
CREATE INDEX IF NOT EXISTS idx_road_observations_segment ON road_risk_observations(road_segment_id, observed_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_road_segments_key ON road_segments(road_segment_key);
CREATE UNIQUE INDEX IF NOT EXISTS idx_risk_events_fall_event_id
    ON risk_events(fall_event_id)
    WHERE fall_event_id IS NOT NULL AND TRIM(fall_event_id) <> '';
