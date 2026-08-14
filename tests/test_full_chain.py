import os
import sys
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import main
from deep_model import score_deep_risk


def frame(down_cm=None, **kwargs):
    data = dict(
        device_id="cane_test",
        lat=31.0,
        lng=121.0,
        front_cm=200,
        left_cm=120,
        right_cm=120,
        down_cm=down_cm,
        source="test",
    )
    data.update(kwargs)
    return main.SensorFrameCreate(**data)


def test_down_boundaries_main_analysis():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    expected = {
        19: "none",
        20: "none",
        70: "none",
        89: "none",
        90: "none",
            91: "none",
            100: "none",
            390: "none",
        None: "none",
    }
    for down, risk_type in expected.items():
        result = main.analyze_sensor_frame(frame(down), history)
        assert result["risk_type"] == risk_type, (down, result)
    assert main.analyze_sensor_frame(frame(91), history)["risk_level"] == "low"
    assert main.analyze_sensor_frame(frame(19), history)["risk_level"] == "low"


def test_down_distance_never_creates_step_without_firmware_ground_state():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}

    def run(values):
        main.reset_runtime_detectors()
        return [
            main.analyze_sensor_frame(
                frame(v, down_raw_cm=v, down_valid=True, down_status="no_target" if v == 400 else "valid"),
                history,
            )["risk_type"]
            for v in values
        ]

    assert run([55] * 5 + [74, 74])[-1] == "none"
    assert run([55] * 5 + [75, 75])[-1] == "none"
    assert run([55] * 5 + [151, 151])[-1] == "none"
    assert run([55] * 5 + [400, 400])[-1] == "none"
    assert run([55] * 5 + [35, 35])[-1] == "none"


def test_deep_model_down_boundaries():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    for down in [20, 70, 89, 90, 91, 100, 390, None]:
        req = SimpleNamespace(risk_type="none", manual_risk_type="none", alert_type=None, fall_detected=False, front_cm=200, left_cm=120, right_cm=120, down_cm=down)
        result = score_deep_risk(req, history)
        assert result["level"] == "low", (down, result)
        assert result["score"] < 0.56, (down, result)
    req = SimpleNamespace(risk_type="ground_step", manual_risk_type=None, alert_type=None, fall_detected=False, front_cm=200, left_cm=120, right_cm=120, down_cm=75)
    assert score_deep_risk(req, history)["level"] == "medium"


def test_side_alert_boundary_is_exactly_35cm():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    assert main.analyze_sensor_frame(frame(55, left_cm=36), history)["risk_type"] == "none"
    assert main.analyze_sensor_frame(frame(55, left_cm=35), history)["risk_type"] == "left_obstacle"
    assert main.analyze_sensor_frame(frame(55, right_cm=36), history)["risk_type"] == "none"
    assert main.analyze_sensor_frame(frame(55, right_cm=35), history)["risk_type"] == "right_obstacle"


def test_front_warns_at_120cm_and_firmware_ground_direction_is_preserved():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    assert main.analyze_sensor_frame(frame(55, front_cm=121), history)["risk_type"] == "none"
    assert main.analyze_sensor_frame(frame(55, front_cm=120), history)["risk_type"] == "front_obstacle"
    up = main.analyze_sensor_frame(frame(
        42, risk_type="ground_step", direction="up", compensated_down_cm=42,
        ground_baseline_cm=55, height_delta_cm=-13, ground_state="GROUND_STEP_UP"
    ), history)
    assert up["risk_type"] == "ground_step"
    assert up["direction"] == "up"
    assert up["voice_prompt"] == "前方障碍，请减速"
    down = main.analyze_sensor_frame(frame(
        68, risk_type="ground_step", direction="down", compensated_down_cm=68,
        ground_baseline_cm=55, height_delta_cm=13, ground_state="GROUND_STEP_DOWN"
    ), history)
    assert down["risk_type"] == "ground_step"
    assert down["direction"] == "down"
    assert down["voice_prompt"] == "前方落差，请减速"
    drop = main.analyze_sensor_frame(frame(
        86, risk_type="ground_drop", direction="down", compensated_down_cm=86,
        ground_baseline_cm=55, height_delta_cm=31, ground_state="GROUND_DROP"
    ), history)
    assert drop["risk_type"] == "ground_drop"
    assert drop["direction"] == "down"


def test_fall_lock_suppresses_distance_feedback_without_time_cooldown(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_lock.db")
    main.init_db()
    locked = frame(
        55, front_cm=20, fall_pending=True, fall_detected=False,
        fall_stage="fall_lying_wait", fall_event_id="fall-lock-1"
    )
    response = main.create_sensor_frame(locked, lite=True)
    assert response["risk_type"] == "none"
    assert response["device_state"]["fallPending"] is True
    recovered = frame(55, front_cm=20, fall_pending=False, fall_detected=False, fall_stage="normal_use_recovered")
    response = main.create_sensor_frame(recovered, lite=True)
    assert response["risk_type"] == "front_obstacle"


def test_fall_and_sos_not_road_intrinsic(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "test.db")
    main.init_db()
    event = {
        "device_id": "cane_test",
        "risk_type": "fall_detected",
        "risk_level": "high",
        "lat": 31.0,
        "lng": 121.0,
        "timestamp": main.now_iso(),
        "confidence": 0.9,
    }
    main.maybe_store_road_observation(event)
    with main.db() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM road_risk_observations").fetchone()["c"] == 0
    event["risk_type"] = "sos"
    main.maybe_store_road_observation(event)
    with main.db() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM road_risk_observations").fetchone()["c"] == 0


def test_mobile_observer_location_counts_as_recent_heartbeat(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "mobile_observer.db")
    main.init_db()
    observer_id = "mobile_ac_user"
    main.create_location(
        main.LocationCreate(
            device_id=observer_id,
            lat=31.0,
            lng=121.0,
            provider="gps",
            quality="usable",
            accuracy_m=5.0,
        )
    )
    assert main.device_has_recent_heartbeat(observer_id) is True


def test_road_risk_multi_device_aggregation(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "test.db")
    main.init_db()
    seg = main.create_local_road_segment(31.0, 121.0)
    for device in ["cane_a", "cane_b"]:
        main.maybe_store_road_observation({
            "device_id": device,
            "risk_type": "ground_step",
            "risk_level": "medium",
            "lat": 31.0,
            "lng": 121.0,
            "timestamp": main.now_iso(),
            "confidence": 0.8,
        })
    score = main.recalculate_road_risk_score(seg)
    assert score["risk_score"] > 0
    with main.db() as conn:
        row = conn.execute("SELECT * FROM road_risk_scores WHERE road_segment_id = ?", (seg,)).fetchone()
        assert row["unique_device_count"] >= 2


def test_navigation_session_update(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "test.db")
    main.init_db()
    route = {
        "origin": {"lat": 31.0, "lng": 121.0},
        "destination": {"lat": 31.001, "lng": 121.0},
        "best_route": {
            "polyline": [{"lat": 31.0, "lng": 121.0}, {"lat": 31.001, "lng": 121.0}],
            "steps": [{"road": "测试路", "distance": "100", "polyline": "121.0,31.0;121.0,31.001"}],
        },
    }
    sid = main.create_navigation_session("cane_test", "user_test", route, "终点")
    result = main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.0005, lng=121.0))
    assert result["success"] is True
    assert result["current_step_index"] == 0
    assert result["should_replan"] is False


def test_navigation_requires_five_off_route_and_three_arrival_frames(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "navigation_state.db")
    main.init_db()
    route = {
        "origin": {"lat": 31.0, "lng": 121.0},
        "destination": {"lat": 31.001, "lng": 121.0},
        "best_route": {
            "polyline": [{"lat": 31.0, "lng": 121.0}, {"lat": 31.001, "lng": 121.0}],
            "steps": [{"road": "测试路", "road_segment_id": None, "distance": "100", "polyline": "121.0,31.0;121.0,31.001"}],
        },
    }
    sid = main.create_navigation_session("cane_real", "user", route, "终点")
    for count in range(1, 6):
        update = main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.0005, lng=121.001))
        assert update["off_route_count"] == count
        assert update["should_replan"] is (count == 5)
    for count in range(1, 4):
        update = main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.001, lng=121.0))
        assert update["arrival_count"] == count
        assert update["arrived"] is (count == 3)


def test_navigation_aligns_phone_gps_to_amap_route_and_exposes_active_session(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "navigation_alignment.db")
    main.init_db()
    route = {
        "origin": {"lat": 39.0, "lng": 116.0, "coordsys": "gps"},
        "destination": {"lat": 39.001, "lng": 116.0, "coordsys": "amap"},
        "best_route": {
            "polyline": [{"lat": 39.002, "lng": 116.003}, {"lat": 39.003, "lng": 116.003}],
            "steps": [{"road": "测试路", "distance": "100", "polyline": "116.003,39.002;116.003,39.003"}],
        },
    }
    sid = main.create_navigation_session("cane_alignment", "user", route, "终点")
    update = main.update_navigation_session(
        sid,
        main.NavigationSessionUpdate(lat=39.0005, lng=116.0, accuracy_m=20, distance_delta_m=2),
    )
    assert update["distance_to_route_m"] < 2
    assert update["off_route_count"] == 0
    active = main.active_navigation("cane_alignment")
    assert active["active"] is True
    assert active["session"]["motion_status"] == "walking"
    assert active["session"]["route_polyline"] == route["best_route"]["polyline"]
    main.stop_navigation_session(sid)
    assert main.active_navigation("cane_alignment")["active"] is False


def test_stale_navigation_is_hidden_from_companion_map(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "stale_navigation.db")
    main.init_db()
    route = {
        "origin": {"lat": 31.0, "lng": 121.0},
        "destination": {"lat": 31.001, "lng": 121.0},
        "best_route": {
            "polyline": [{"lat": 31.0, "lng": 121.0}, {"lat": 31.001, "lng": 121.0}],
            "steps": [{"road": "测试路", "distance": "100", "polyline": "121.0,31.0;121.0,31.001"}],
        },
    }
    sid = main.create_navigation_session("cane_stale", "user", route, "终点")
    with main.db() as conn:
        conn.execute(
            "UPDATE navigation_sessions SET updated_at = ? WHERE session_id = ?",
            ("2020-01-01T00:00:00+00:00", sid),
        )
    assert main.active_navigation("cane_stale")["active"] is False


def test_navigation_traversal_lifecycle(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "traversal.db")
    main.init_db()
    segment = main.upsert_road_segment_from_step(
        {"road": "真实测试道路", "distance": "100", "polyline": "121.0,31.0;121.0,31.001"}
    )
    route = {
        "origin": {"lat": 31.0, "lng": 121.0},
        "destination": {"lat": 31.001, "lng": 121.0},
        "best_route": {
            "polyline": [{"lat": 31.0, "lng": 121.0}, {"lat": 31.001, "lng": 121.0}],
            "steps": [{"road": "真实测试道路", "road_segment_id": segment, "distance": "100", "polyline": "121.0,31.0;121.0,31.001"}],
        },
    }
    sid = main.create_navigation_session("cane_real", None, route, "终点")
    main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.0002, lng=121.0))
    with main.db() as conn:
        active = conn.execute("SELECT * FROM road_traversals WHERE navigation_session_id = ?", (sid,)).fetchone()
    assert active and active["status"] == "active"
    main.stop_navigation_session(sid)
    with main.db() as conn:
        stopped = conn.execute("SELECT * FROM road_traversals WHERE id = ?", (active["id"],)).fetchone()
    assert stopped["status"] == "cancelled"
    assert stopped["safe_pass"] == 0


def test_pending_fall_is_visible_without_formal_alert(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_state.db")
    main.init_db()
    pending = frame(
        55,
        fall_event_id="fall-pending-1",
        fall_pending=True,
        fall_detected=False,
        fall_stage="slow_fall_cancel_pending",
        fall_confidence=0.72,
    )
    analysis = main.analyze_sensor_frame(pending, {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"})
    stored = main.upsert_device_state(pending, 31.0, 121.0, analysis)
    assert stored["fallPending"] is True
    assert stored["fallDetected"] is False
    assert stored["fallStage"] == "slow_fall_cancel_pending"


def test_fall_event_id_is_persistently_deduplicated(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_dedup.db")
    main.init_db()
    event = main.EventCreate(
        device_id="cane_real", lat=31.0, lng=121.0,
        risk_type="fall_detected", risk_level="high",
        fall_event_id="fall-stable-id-1",
    )
    first = main.store_event(event)
    main.reset_runtime_detectors()
    second = main.store_event(event)
    assert first["id"] == second["id"]
    with main.db() as conn:
        assert conn.execute(
            "SELECT COUNT(*) AS c FROM risk_events WHERE fall_event_id = ?",
            ("fall-stable-id-1",),
        ).fetchone()["c"] == 1


def test_fall_detected_becomes_high_shared_risk_point_and_warns_other_device(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_shared_risk.db")
    main.init_db()
    event = main.EventCreate(
        device_id="cane_device_a",
        lat=31.0,
        lng=121.0,
        risk_type="fall_detected",
        risk_level="low",
        fall_event_id="fall-shared-risk-1",
    )

    first = main.store_event(event)
    second = main.store_event(event)

    assert first["id"] == second["id"]
    with main.db() as conn:
        event_count = conn.execute(
            "SELECT COUNT(*) AS c FROM risk_events WHERE fall_event_id = ?",
            ("fall-shared-risk-1",),
        ).fetchone()["c"]
        points = conn.execute(
            "SELECT * FROM risk_points WHERE risk_type = ?",
            ("fall_detected",),
        ).fetchall()

    assert event_count == 1
    assert len(points) == 1
    point = points[0]
    assert point["risk_type"] == "fall_detected"
    assert point["risk_level"] == "high"
    assert point["report_count"] == 1
    assert point["latest_event_id"] == first["id"]
    assert main.parse_devices_json(point["source_devices_json"]) == ["cane_device_a"]
    ttl_seconds = (main.parse_time(point["expires_at"]) - main.parse_time(point["last_reported_at"])).total_seconds()
    assert ttl_seconds == 7 * 24 * 60 * 60

    warning = main.nearby_risk_warning(
        lat=31.0,
        lng=121.00002,
        radius=50,
        min_level="medium",
        exclude_device_id=None,
        bearing_deg=None,
    )
    assert warning["found"] is True
    assert warning["warning"]["riskType"] == "fall_detected"
    assert warning["warning"]["riskLevel"] == "high"


def test_fall_detected_with_filtered_mock_coordinates_saves_event_without_risk_point(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_filtered_location.db")
    main.init_db()

    stored = main.store_event(main.EventCreate(
        device_id="cane_device_a",
        lat=main.LEGACY_SIM_POINT_LAT,
        lng=main.LEGACY_SIM_POINT_LNG,
        risk_type="fall_detected",
        risk_level="low",
        fall_event_id="fall-filtered-location-1",
    ))

    with main.db() as conn:
        event_count = conn.execute(
            "SELECT COUNT(*) AS c FROM risk_events WHERE id = ?",
            (stored["id"],),
        ).fetchone()["c"]
        point_count = conn.execute("SELECT COUNT(*) AS c FROM risk_points").fetchone()["c"]
    assert event_count == 1
    assert point_count == 0


def test_latest_mobile_location_skips_multiple_newer_untrusted_locations(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "mobile_location_history.db")
    main.init_db()
    now = main.datetime.now(main.timezone.utc)
    device_id = "cane_location_a"
    trusted = main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=31.1,
        lng=121.1,
        source="android",
        provider="amap",
        quality="usable",
        timestamp=(now - main.timedelta(seconds=120)).isoformat(timespec="seconds"),
    ))
    main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=main.LEGACY_SIM_POINT_LAT,
        lng=main.LEGACY_SIM_POINT_LNG,
        source="esp32c5",
        provider="mock",
        quality="mock",
        timestamp=(now - main.timedelta(seconds=30)).isoformat(timespec="seconds"),
    ))
    main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=30.0,
        lng=120.0,
        source="test",
        provider="simulator",
        quality="demo",
        timestamp=(now - main.timedelta(seconds=10)).isoformat(timespec="seconds"),
    ))

    selected = main.latest_mobile_location_for_device(device_id)

    assert selected["id"] == trusted["id"]
    assert (selected["lat"], selected["lng"]) == (31.1, 121.1)


def test_latest_mobile_location_rejects_stale_trusted_location_and_preserves_fallback(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "mobile_location_fallback.db")
    main.init_db()
    now = main.datetime.now(main.timezone.utc)
    device_id = "cane_location_b"
    trusted = main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=31.2,
        lng=121.2,
        source="android",
        provider="amap",
        quality="usable",
        timestamp=(now - main.timedelta(seconds=301)).isoformat(timespec="seconds"),
    ))
    with main.db() as conn:
        conn.execute(
            "UPDATE device_locations SET timestamp = ? WHERE id = ?",
            ((now - main.timedelta(seconds=301)).isoformat(timespec="seconds"), trusted["id"]),
        )
    mock = main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=main.LEGACY_SIM_POINT_LAT,
        lng=main.LEGACY_SIM_POINT_LNG,
        source="esp32c5",
        provider="mock",
        quality="mock",
        timestamp=now.isoformat(timespec="seconds"),
    ))

    assert main.latest_mobile_location_for_device(device_id) is None
    assert main.prefer_mobile_location(device_id, mock["lat"], mock["lng"]) == (mock["lat"], mock["lng"])

    mock_only_device = "cane_location_c"
    mock_only = main.create_location(main.LocationCreate(
        device_id=mock_only_device,
        lat=30.5,
        lng=120.5,
        source="esp32c5",
        provider="mock",
        quality="mock",
        timestamp=now.isoformat(timespec="seconds"),
    ))
    assert main.latest_mobile_location_for_device(mock_only_device) is None
    assert main.prefer_mobile_location(
        mock_only_device, mock_only["lat"], mock_only["lng"]
    ) == (mock_only["lat"], mock_only["lng"])


def test_sos_uses_recent_trusted_mobile_location_behind_newer_mock(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "sos_mobile_location.db")
    main.init_db()
    now = main.datetime.now(main.timezone.utc)
    device_id = "cane_device_a"
    real_lat, real_lng = 31.1, 121.1
    main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=real_lat,
        lng=real_lng,
        source="android",
        provider="amap",
        quality="usable",
        timestamp=(now - main.timedelta(seconds=60)).isoformat(timespec="seconds"),
    ))
    main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=main.LEGACY_SIM_POINT_LAT,
        lng=main.LEGACY_SIM_POINT_LNG,
        source="esp32c5",
        provider="mock",
        quality="mock",
        timestamp=now.isoformat(timespec="seconds"),
    ))

    stored = main.create_risk_event(main.EventCreate(
        device_id=device_id,
        lat=main.LEGACY_SIM_POINT_LAT,
        lng=main.LEGACY_SIM_POINT_LNG,
        risk_type="sos",
        risk_level="high",
    ))

    with main.db() as conn:
        event_row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (stored["id"],)).fetchone()
        point = conn.execute("SELECT * FROM risk_points WHERE latest_event_id = ?", (stored["id"],)).fetchone()
    assert event_row is not None
    assert (event_row["lat"], event_row["lng"]) == (real_lat, real_lng)
    assert point is not None
    assert (point["lat"], point["lng"]) == (real_lat, real_lng)
    assert point["risk_type"] == "sos"
    assert point["risk_level"] == "high"
    assert device_id in main.parse_devices_json(point["source_devices_json"])


def test_fall_lock_suppresses_other_sensor_alerts_until_firmware_recovery(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_suppression.db")
    main.init_db()
    response = main.create_sensor_frame(frame(
        55, device_id="cane_real", front_cm=10, down_raw_cm=55,
        down_valid=True, down_status="valid", fall_pending=True,
        fall_stage="fall_lying_wait", fall_event_id="fall-exclusive-1",
    ), lite=False)
    assert response["risk"]["risk_type"] == "none"
    assert response["risk"]["voice_prompt"] == ""
    assert response["stored_event"] is None


def test_navigation_returns_distance_to_next_action(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "next_action.db")
    main.init_db()
    route = {
        "origin": {"lat": 31.0, "lng": 121.0},
        "destination": {"lat": 31.001, "lng": 121.0},
        "best_route": {
            "polyline": [{"lat": 31.0, "lng": 121.0}, {"lat": 31.001, "lng": 121.0}],
            "steps": [{"road": "测试路", "distance": "100", "instruction": "右转",
                       "polyline": "121.0,31.0;121.0,31.001"}],
        },
    }
    sid = main.create_navigation_session("cane_real", None, route, "终点")
    result = main.update_navigation_session(
        sid, main.NavigationSessionUpdate(lat=31.00075, lng=121.0, distance_delta_m=3.0)
    )
    assert 20 <= result["distance_to_next_action_m"] <= 35


def test_firmware_source_contains_local_step_and_fall_contract():
    firmware = (ROOT / "firmware" / "smartcane_arduino" / "risk_logic.cpp").read_text(encoding="utf-8")
    config = (ROOT / "firmware" / "smartcane_arduino" / "config.h").read_text(encoding="utf-8")
    sketch = (ROOT / "firmware" / "smartcane_arduino" / "smartcane_arduino.ino").read_text(encoding="utf-8")
    assert "SMARTCANE_STEP_UP_ENTER_CM 9" in config
    assert "SMARTCANE_FRONT_WARN_CM 120" in config
    assert "SMARTCANE_STEP_DOWN_ENTER_CM 50" in config
    assert "SMARTCANE_DEEP_DROP_CM 70" in config
    assert "SMARTCANE_STEP_NORMAL_POSE_SETTLE_MS 250" in config
    assert "SMARTCANE_DOWN_STARTUP_RELEARN_MS 1500" in config
    assert "SMARTCANE_SIDE_ALERT_CM 35" in config
    assert "SMARTCANE_DOWN_NO_TARGET_CM 400" in config
    assert "lastHeightDeltaCm >= SMARTCANE_STEP_DOWN_ENTER_CM" in firmware
    assert "lastHeightDeltaCm <= -SMARTCANE_STEP_UP_ENTER_CM" in firmware
    assert "cm > SMARTCANE_DOWN_LONG_DISTANCE_ALARM_CM" not in firmware
    assert "rawCm >= SMARTCANE_DOWN_NO_TARGET_CM" in firmware
    imu = (ROOT / "firmware" / "smartcane_arduino" / "imu_fall.cpp").read_text(encoding="utf-8")
    assert "FALL_STAGE_CANDIDATE" in imu
    assert "REG_ACC_X_LSB = 0x0C" in imu
    assert "REG_GYR_X_LSB = 0x12" in imu
    assert "SMARTCANE_FALL_CONFIRM_MS 2000" in config
    assert "SMARTCANE_FALL_ALERT_BUZZ_MS 2000" in config
    assert "SMARTCANE_FALL_ALERT_VIB_MS 2000" in config
    assert "beginFallCandidate" in imu
    assert "candidate_expired_without_lying" in imu
    assert "fall_confirmed" in sketch and "fall_detected" in sketch
    assert "fallLockActive" in sketch
    assert "beep(SMARTCANE_FALL_ALERT_BUZZ_MS);" in sketch
    assert "vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, SMARTCANE_FALL_ALERT_VIB_MS);" in sketch
    assert "applyFeedbackForRisk(currentRisk, true, true);" in sketch
    assert "publishLocalCueEvent(currentRisk, persistent, shouldBuzzForRisk(currentRisk));" in sketch
    loop = sketch[sketch.index("void loop()") :]
    assert "if (updateRiskFeedbackGate(currentRisk, persistent))" in loop
    assert loop.index("applyFeedbackForRisk(currentRisk, true, true);") < loop.index("publishLocalCueEvent(currentRisk")
    assert "buzzerStop();" in sketch
    vibration = (ROOT / "firmware" / "smartcane_arduino" / "vibration.cpp").read_text(encoding="utf-8")
    assert "vibrateIndex(0, level, durationMs);" in vibration


def test_medium_and_high_obstacles_can_become_shared_risk_points():
    assert main.map_weight_for_risk("left_obstacle", "low", 25.0) == 8.0
    assert main.map_weight_for_risk("right_obstacle", "medium", 62.0) >= 60.0
    assert main.map_weight_for_risk("front_obstacle", "high", 80.0) >= 70.0
    assert main.should_store_sensor_analysis({
        "risk_type": "right_obstacle",
        "risk_level": "medium",
        "map_weight": 62.0,
    })
    main.reset_runtime_detectors()
    analysis = main.analyze_sensor_frame(
        frame(55, right_cm=20, down_raw_cm=55, down_valid=True, down_status="valid"),
        {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"},
    )
    assert analysis["risk_type"] == "right_obstacle"
    assert analysis["risk_level"] == "high"
    assert analysis["map_weight"] >= 70
    assert main.should_store_sensor_analysis(analysis)


def test_periodic_real_frame_updates_state_without_creating_event(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "periodic_frame.db")
    main.init_db()
    response = main.create_sensor_frame(frame(
        55,
        device_id="cane_real",
        source="esp32c5",
        front_cm=25,
        risk_type="front_obstacle",
        risk_level="high",
        manual_risk_type="front_obstacle",
        manual_risk_level="high",
        extra="source=periodic_real_frame",
    ), lite=False)
    assert response["risk"]["risk_type"] == "front_obstacle"
    assert response["stored_event"] is None
    with main.db() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM risk_events").fetchone()["c"] == 0


def test_device_event_cursor_returns_only_new_events_in_order(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "event_cursor.db")
    main.init_db()
    first = main.store_event(main.EventCreate(
        device_id="cane_real", lat=31.0, lng=121.0,
        risk_type="front_obstacle", risk_level="medium",
        sensor="tof_front", extra_json={"source": "auto_detected_once_per_place"},
    ))
    second = main.store_event(main.EventCreate(
        device_id="cane_real", lat=31.0, lng=121.0,
        risk_type="ground_step", risk_level="high",
        sensor="tof_down", extra_json={"source": "auto_detected_once_per_place"},
    ))
    main.store_event(main.EventCreate(
        device_id="another_cane", lat=31.0, lng=121.0,
        risk_type="sos", risk_level="high", sensor="sos_button",
    ))

    response = main.events_since(device_id="cane_real", sinceId=first["id"], limit=50)
    assert [event["id"] for event in response["events"]] == [second["id"]]
    assert response["events"][0]["source"] == "auto_detected_once_per_place"
    assert response["lastId"] == second["id"]


def test_alert_payload_marks_old_events_as_not_fresh_for_speech(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "alert_freshness.db")
    main.init_db()
    old = main.store_event(main.EventCreate(
        device_id="cane_real", lat=31.0, lng=121.0,
        risk_type="sos", risk_level="high", sensor="sos_button",
        timestamp="2026-01-01T00:00:00+00:00",
    ))
    with main.db() as conn:
        conn.execute("UPDATE risk_events SET timestamp = ? WHERE id = ?", ("2026-01-01T00:00:00+00:00", old["id"]))
        row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (old["id"],)).fetchone()
    assert main.alert_event_payload(row, "blind")["freshForSpeech"] is False


def test_sos_becomes_historical_risk_point(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "sos_history.db")
    main.init_db()
    stored = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="sos",
        risk_level="high",
        extra_json={"source": "esp32c5"},
    ))
    points = main.active_risk_points(31.0, 121.0, radius=20, limit=10)
    assert stored["risk_type"] == "sos"
    assert any(point["riskType"] == "sos" for point in points)
    warning = main.nearby_risk_warning(lat=31.0, lng=121.00002, radius=50, min_level="medium", exclude_device_id="cane_real", bearing_deg=None)
    assert warning["found"] is True
    assert "求助风险" in warning["warning"]["voicePrompt"]
    assert len(warning["warning"]["voicePrompt"]) <= 15


def test_all_map_risk_points_have_seven_day_lifetime():
    expected = 7 * 24 * 60 * 60
    for risk_type, level in (
        ("front_obstacle", "low"),
        ("front_obstacle", "high"),
        ("ground_drop", "medium"),
        ("user_mark", "medium"),
        ("sos", "high"),
        ("fall_detected", "high"),
    ):
        assert main.risk_point_ttl_seconds(risk_type, level) == expected


def test_map_returns_latest_points_instead_of_old_high_risk_points(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "map_latest_queue.db")
    main.init_db()
    high = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="sos",
        risk_level="high",
        extra_json={"source": "esp32c5"},
    ))
    low = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.01,
        lng=121.01,
        risk_type="front_obstacle",
        risk_level="low",
        extra_json={"source": "esp32c5"},
    ))
    current_time = main.datetime.now(main.timezone.utc).replace(microsecond=0)
    with main.db() as conn:
        conn.execute(
            "UPDATE risk_points SET last_reported_at = ? WHERE latest_event_id = ?",
            ((current_time - main.timedelta(minutes=1)).isoformat(), high["id"]),
        )
        conn.execute(
            "UPDATE risk_points SET last_reported_at = ? WHERE latest_event_id = ?",
            (current_time.isoformat(), low["id"]),
        )

    response = main.map_risk_points(lat=None, lng=None, radius=500.0, limit=1)

    assert response["risk_count"] == 1
    assert response["points"][0]["riskType"] == "front_obstacle"
    assert response["points"][0]["riskLevel"] == "low"


def test_expired_map_points_do_not_fall_back_to_raw_history(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "map_expiry_no_fallback.db")
    main.init_db()
    stored = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="front_obstacle",
        risk_level="medium",
        extra_json={"source": "esp32c5"},
    ))
    with main.db() as conn:
        conn.execute(
            "UPDATE risk_points SET expires_at = ?, status = 'active' WHERE latest_event_id = ?",
            ("2026-01-01T00:00:00+00:00", stored["id"]),
        )

    response = main.map_risk_points(lat=None, lng=None, radius=500.0, limit=200)
    assert response["clustered"] is True
    assert response["risk_count"] == 0
    assert response["points"] == []
    with main.db() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM risk_events").fetchone()["c"] == 1


def test_existing_points_are_migrated_to_seven_days_from_last_report(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "map_expiry_migration.db")
    main.init_db()
    recent = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="front_obstacle",
        risk_level="medium",
        extra_json={"source": "esp32c5"},
    ))
    old = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.01,
        lng=121.01,
        risk_type="ground_drop",
        risk_level="medium",
        extra_json={"source": "esp32c5"},
    ))
    current_time = main.datetime.now(main.timezone.utc).replace(microsecond=0)
    recent_time = current_time - main.timedelta(days=6)
    old_time = current_time - main.timedelta(days=8)
    with main.db() as conn:
        conn.execute(
            "UPDATE risk_points SET last_reported_at = ?, status = 'expired' WHERE latest_event_id = ?",
            (recent_time.isoformat(timespec="seconds"), recent["id"]),
        )
        conn.execute(
            "UPDATE risk_points SET last_reported_at = ?, status = 'active' WHERE latest_event_id = ?",
            (old_time.isoformat(timespec="seconds"), old["id"]),
        )

    main.normalize_risk_point_expirations()

    with main.db() as conn:
        recent_point = conn.execute("SELECT * FROM risk_points WHERE latest_event_id = ?", (recent["id"],)).fetchone()
        old_point = conn.execute("SELECT * FROM risk_points WHERE latest_event_id = ?", (old["id"],)).fetchone()
    assert recent_point["status"] == "active"
    assert main.parse_time(recent_point["expires_at"]) == recent_time + main.timedelta(days=7)
    assert old_point["status"] == "expired"


def test_non_navigation_warning_excludes_risk_points_beyond_ten_meters(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "ten_meter_warning.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_other",
        lat=31.000135,
        lng=121.0,
        risk_type="ground_drop",
        risk_level="high",
        extra_json={"source": "esp32c5"},
    ))

    warning = main.nearby_risk_warning(
        lat=31.0,
        lng=121.0,
        radius=50,
        min_level="medium",
        exclude_device_id="cane_real",
        bearing_deg=None,
    )

    assert warning["found"] is False
    assert warning["radius_m"] == main.REALTIME_NEARBY_WARNING_RADIUS_M
    assert warning["requested_radius_m"] == 50
    assert main.active_risk_points(31.0, 121.0, radius=50, limit=10)


def test_offline_cane_cannot_request_historical_risk_speech(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "offline_nearby_warning.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_other",
        lat=31.0,
        lng=121.0,
        risk_type="ground_drop",
        risk_level="high",
        extra_json={"source": "esp32c5"},
    ))

    warning = main.nearby_risk_warning(
        lat=31.0,
        lng=121.0,
        radius=10,
        min_level="medium",
        exclude_device_id="cane_offline",
        bearing_deg=None,
    )

    assert warning["found"] is False
    assert warning["suppressed_reason"] == "device_offline"


def test_expired_sos_stays_historical_but_is_not_a_current_alert(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "expired_sos.db")
    main.init_db()
    old_timestamp = (main.datetime.now(main.timezone.utc) - main.timedelta(minutes=10)).isoformat(timespec="seconds")
    stored = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="sos",
        risk_level="high",
        voice_prompt="SOS 已发送",
        timestamp=old_timestamp,
        extra_json={"source": "esp32c5"},
    ))
    with main.db() as conn:
        conn.execute("UPDATE risk_events SET timestamp = ? WHERE id = ?", (old_timestamp, stored["id"]))
        conn.execute("UPDATE device_state SET updated_at = ? WHERE device_id = ?", (old_timestamp, "cane_real"))

    state = main.latest_device_state(device_id="cane_real")["state"]
    alerts = main.latest_alerts(role="blind", userId=None, deviceId="cane_real", sinceId=0, limit=20)
    points = main.active_risk_points(31.0, 121.0, radius=20, limit=10)

    assert state["riskType"] == "none"
    assert state["riskLevel"] == "low"
    assert state["voicePrompt"] == "当前未发现明显风险"
    assert alerts["alerts"] == []
    assert any(point["riskType"] == "sos" for point in points)


def test_old_event_fallback_does_not_report_device_online(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "offline_fallback.db")
    main.init_db()
    old_timestamp = (main.datetime.now(main.timezone.utc) - main.timedelta(minutes=10)).isoformat(timespec="seconds")
    stored = main.store_event(main.EventCreate(
        device_id="cane_offline",
        lat=31.0,
        lng=121.0,
        risk_type="ground_drop",
        risk_level="high",
        timestamp=old_timestamp,
        extra_json={"source": "esp32c5"},
    ))
    with main.db() as conn:
        conn.execute("UPDATE risk_events SET timestamp = ? WHERE id = ?", (old_timestamp, stored["id"]))
        conn.execute("DELETE FROM device_state WHERE device_id = ?", ("cane_offline",))

    state = main.latest_device_state(device_id="cane_offline")["state"]

    assert stored["timestamp"] != old_timestamp
    assert stored["sourceTimestamp"] == old_timestamp
    assert state["online"] is False


def test_server_time_is_canonical_and_client_time_is_audit_only(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "server_time.db")
    main.init_db()
    client_time = "2001-01-01T00:00:00+00:00"

    stored = main.store_event(main.EventCreate(
        device_id="cane_user_a",
        lat=31.0,
        lng=121.0,
        risk_type="user_mark",
        risk_level="medium",
        timestamp=client_time,
    ))
    point = main.active_risk_points(31.0, 121.0, radius=20, limit=10)[0]

    assert stored["sourceTimestamp"] == client_time
    assert stored["timestamp"] != client_time
    assert point["timestamp"] == stored["timestamp"]


def test_other_account_observer_sees_user_mark_and_its_update(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "cross_account_mark.db")
    main.init_db()
    main.create_location(main.LocationCreate(
        device_id="mobile_user_companion_b",
        lat=31.0,
        lng=121.00002,
        source="android_app",
    ))
    first = main.store_event(main.EventCreate(
        device_id="cane_user_a",
        lat=31.0,
        lng=121.0,
        risk_type="user_mark",
        risk_level="medium",
    ))

    first_warning = main.nearby_risk_warning(
        lat=31.0,
        lng=121.00002,
        radius=10,
        min_level="medium",
        observer_id="mobile_user_companion_b",
        exclude_source_device_ids="cane_user_b",
        exclude_device_id=None,
        bearing_deg=None,
    )
    second = main.store_event(main.EventCreate(
        device_id="cane_user_a",
        lat=31.000001,
        lng=121.0,
        risk_type="user_mark",
        risk_level="high",
    ))
    updated_point = main.active_risk_points(31.0, 121.0, radius=20, limit=10)[0]

    assert first_warning["found"] is True
    assert first_warning["warning"]["deviceId"] == "cane_user_a"
    assert second["id"] > first["id"]
    assert updated_point["reportCount"] == 2
    assert updated_point["riskLevel"] == "high"
    assert updated_point["timestamp"] == second["timestamp"]


def test_registered_account_exposes_both_app_roles(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "dual_roles.db")
    main.init_db()

    response = main.register_user(main.AuthRegisterRequest(
        account="dual_mode_user",
        password="test-password",
        displayName="AC",
        role="companion",
    ))

    assert response["user"]["account"] == "dual_mode_user"
    assert response["user"]["displayName"] == "AC"
    assert response["user"]["role"] == "companion"
    assert response["user"]["roles"] == ["blind", "companion"]


def test_voice_request_triggers_interaction_but_is_not_a_risk_record(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "voice_request_filter.db")
    main.init_db()
    stored = main.store_event(main.EventCreate(
        device_id="cane_voice",
        lat=31.0,
        lng=121.0,
        risk_type="voice_request",
        risk_level="low",
        voice_prompt="请说目的地或指令",
    ))

    assert stored["risk_type"] == "voice_request"
    assert main.legacy_latest_events(limit=20)["events"] == []
    blind_alerts = main.latest_alerts(role="blind", userId=None, deviceId="cane_voice", sinceId=0, limit=20)
    companion_alerts = main.latest_alerts(role="companion", userId=None, deviceId="cane_voice", sinceId=0, limit=20)
    assert blind_alerts["alerts"][0]["riskType"] == "voice_request"
    assert companion_alerts["alerts"][0]["riskType"] == "voice_request"
    assert blind_alerts["alerts"][0]["targetRoles"] == ["blind", "companion"]
    assert companion_alerts["alerts"][0]["targetRoles"] == ["blind", "companion"]


def test_account_receives_bound_cane_voice_request_in_either_mode(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "dual_mode_alert_scope.db")
    main.init_db()
    timestamp = main.now_iso()
    with main.db() as conn:
        conn.execute(
            """
            INSERT INTO care_relations
                (relation_id, status, blind_user_id, blind_name, companion_user_id,
                 companion_name, device_id, device_name, created_at, updated_at)
            VALUES (?, 'active', ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "relation_dual_mode", "account_a", "A", "account_b", "B",
                "cane_account_a", "Cane A", timestamp, timestamp,
            ),
        )
    main.store_event(main.EventCreate(
        device_id="cane_account_a",
        lat=31.0,
        lng=121.0,
        risk_type="voice_request",
        risk_level="low",
        voice_prompt="请说目的地或指令",
    ))

    for account in ("account_a", "account_b"):
        for role in ("blind", "companion"):
            response = main.latest_alerts(
                role=role, userId=account, deviceId=None, sinceId=0, limit=20
            )
            assert response["devices"] == ["cane_account_a"]
            assert response["alerts"][0]["riskType"] == "voice_request"


def test_location_uses_server_time_and_preserves_phone_heading(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "location_heading.db")
    main.init_db()
    client_time = "2001-01-01T00:00:00+00:00"

    stored = main.create_location(main.LocationCreate(
        device_id="mobile_user_a",
        lat=31.0,
        lng=121.0,
        bearing_deg=123.0,
        timestamp=client_time,
    ))

    assert stored["timestamp"] != client_time
    assert stored["source_timestamp"] == client_time
    assert stored["bearing_deg"] == 123.0


def test_recent_sos_is_still_delivered_as_a_current_alert(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "recent_sos.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="sos",
        risk_level="high",
        voice_prompt="SOS 已发送",
        extra_json={"source": "esp32c5"},
    ))

    state = main.latest_device_state(device_id="cane_real")["state"]
    alerts = main.latest_alerts(role="blind", userId=None, deviceId="cane_real", sinceId=0, limit=20)

    assert state["riskType"] == "sos"
    assert any(alert["riskType"] == "sos" for alert in alerts["alerts"])


def test_low_obstacle_second_report_promotes_to_history_warning(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "low_promotion.db")
    main.init_db()
    for offset in (0.0, 0.000001):
        main.store_event(main.EventCreate(
            device_id="cane_real",
            lat=31.0 + offset,
            lng=121.0,
            risk_type="front_obstacle",
            risk_level="low",
            front_cm=68,
            distance_mm=680,
            extra_json={"source": "esp32c5"},
        ))
    warning = main.nearby_risk_warning(lat=31.0, lng=121.00002, radius=50, min_level="medium", exclude_device_id=None, bearing_deg=None)
    assert warning["found"] is True
    assert warning["warning"]["riskLevel"] == "medium"
    assert warning["warning"]["voicePrompt"] == "前方2米有中风险点"
    assert len(warning["warning"]["voicePrompt"]) <= 15


def test_realtime_voice_templates_only_describe_conditions_and_confirmed_fall():
    pending = frame(55, fall_pending=True, fall_detected=False, fall_stage="fall_candidate")
    confirmed = frame(55, fall_pending=False, fall_detected=True, fall_stage="fall_confirmed")
    assert main.voice_prompt_for_risk(pending, "none", "low", "none") != "检测到跌倒"
    assert main.voice_prompt_for_risk(confirmed, "fall_detected", "high", "stop") == "检测到跌倒"

    realtime_prompts = [
        main.legacy_event_message({"risk_type": "sos"}),
        main.voice_prompt_for_risk(frame(55, front_cm=35), "front_obstacle", "high", "stop"),
        main.voice_prompt_for_risk(frame(55), "ground_drop", "high", "down"),
    ]
    assert realtime_prompts == ["用户发起紧急求助", "前方障碍，请减速", "前方落差，请减速"]
    assert main.legacy_event_message({"risk_type": "sos"}) == "用户发起紧急求助"


def test_realtime_obstacles_steps_and_drop_only_describe_the_condition():
    front = main.voice_prompt_for_risk(frame(55, front_cm=35), "front_obstacle", "high", "stop")
    up_step = main.voice_prompt_for_risk(frame(42), "ground_step", "high", "up")
    down_step = main.voice_prompt_for_risk(frame(68), "ground_step", "high", "down")
    drop = main.voice_prompt_for_risk(frame(86), "ground_drop", "high", "down")

    assert front == "前方障碍，请减速"
    assert up_step == "前方障碍，请减速"
    assert down_step == "前方落差，请减速"
    assert drop == "前方落差，请减速"

    historical_up = main.nearby_warning_text(
        8.0, "high", "front", {"riskType": "ground_step", "voicePrompt": up_step}
    )
    historical_down = main.nearby_warning_text(
        8.0, "high", "front", {"riskType": "ground_step", "voicePrompt": down_step}
    )
    assert historical_up == "前方8米有高风险点"
    assert historical_down == "前方8米有高风险点"


def test_nearby_risk_point_speech_uses_direction_distance_and_existing_level():
    event = {"riskType": "front_obstacle", "reportCount": 2}

    assert main.nearby_warning_text(2.4, "high", "front", event) == "前方2米有高风险点"
    assert main.nearby_warning_text(3.6, "medium", "left", event) == "左侧4米有中风险点"
    assert main.nearby_warning_text(5.2, "low", "right", event) == "右侧5米有低风险点"
    assert "障碍" not in main.nearby_warning_text(2.4, "high", "front", event)


def test_obstacle_advice_never_suggests_lateral_avoidance():
    request = main.AdviceRequest(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="front_obstacle",
        risk_level="high",
        front_cm=120,
        left_cm=120,
        right_cm=30,
    )
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}

    assert main.fallback_advice(request, history) == "前方障碍，请减速"
    assert main.deep_advice(request, {"level": "high"}) == "前方障碍，请减速"
    assert "向左" not in main.fallback_advice(request, history)
    assert "向右" not in main.deep_advice(request, {"level": "high"})


def test_realtime_ai_advice_uses_condition_template_without_llm(monkeypatch):
    async def fail_if_called(*args, **kwargs):
        raise AssertionError("realtime sensor advice must not call the LLM")

    monkeypatch.setattr(main, "call_chat_completion", fail_if_called)
    request = main.AdviceRequest(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="left_obstacle",
        risk_level="medium",
        left_cm=32,
    )
    result = main.asyncio.run(main.generate_advice(
        request,
        {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"},
        {"level": "medium"},
    ))

    assert result["advice"] == "左侧32厘米有障碍"
    assert result["provider"] == "rule"
    assert result["skipped"] == "realtime_condition_only"


def test_local_cue_string_metadata_is_parsed_and_deduplicated(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "local_cue.db")
    main.init_db()
    extra = main.json.dumps({
        "source": "esp32c5_local_cue",
        "schema": "smartcane.local_cue.v1",
        "cue_source": "risk_feedback",
        "is_local_cue": True,
        "cue_id": "cue-device-1",
        "cue_at_ms": 123456,
        "cue_repeat": False,
        "buzzer_requested": True,
        "vibration_requested": True,
    })
    event = main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="ground_step",
        risk_level="medium",
        direction="down",
        sensor="tof_down",
        distance_mm=680,
        extra_json=extra,
    )

    first = main.store_event(event)
    second = main.store_event(event)
    cues = main.local_cues_since(deviceId="cane_real", sinceId=0, limit=50)

    assert first["id"] == second["id"]
    assert len(cues["cues"]) == 1
    assert cues["cues"][0]["eventKind"] == "local_cue"
    assert cues["cues"][0]["cue"]["id"] == "cue-device-1"
    assert cues["cues"][0]["speech"]["shouldSpeak"] is True
    assert cues["cues"][0]["speech"]["text"] == "前方落差，请减速"


def test_local_cue_repeat_and_non_cue_events_never_speak(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "local_cue_repeat.db")
    main.init_db()
    repeat = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="left_obstacle",
        risk_level="low",
        direction="keep_right",
        extra_json={
            "is_local_cue": True,
            "cue_id": "cue-repeat",
            "cue_source": "risk_feedback",
            "cue_repeat": True,
        },
    ))
    main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="right_obstacle",
        risk_level="low",
        direction="keep_left",
        extra_json={"source": "esp32c5_periodic_frame"},
    ))

    with main.db() as conn:
        row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (repeat["id"],)).fetchone()
    payload = main.local_cue_payload(row)
    cues = main.local_cues_since(deviceId="cane_real", sinceId=0, limit=50)

    assert payload["speech"]["shouldSpeak"] is False
    assert [cue["cue"]["id"] for cue in cues["cues"]] == ["cue-repeat"]


def test_only_formal_fall_local_cue_can_speak(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "formal_fall_cue.db")
    main.init_db()
    stored = main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="fall_detected",
        risk_level="high",
        direction="stop",
        sensor="bmi270_imu",
        fall_event_id="fall-001",
        extra_json={
            "is_local_cue": True,
            "cue_id": "fall-001",
            "cue_source": "formal_fall",
            "cue_repeat": False,
            "fall_stage": "fall_confirmed",
        },
    ))
    with main.db() as conn:
        row = conn.execute("SELECT * FROM risk_events WHERE id = ?", (stored["id"],)).fetchone()

    payload = main.local_cue_payload(row)

    assert payload["fall"] == {"detected": True, "eventId": "fall-001"}
    assert payload["speech"] == {"shouldSpeak": True, "text": "检测到跌倒"}


def test_navigation_advice_timeout_is_shorter_than_amap_timeout():
    assert 0 < main.NAVIGATION_ADVICE_TIMEOUT_SECONDS < 12
    assert 0 < main.NAVIGATION_COMMAND_TIMEOUT_SECONDS < 12


def test_explicit_navigation_command_skips_llm(monkeypatch):
    async def fail_if_called(*args, **kwargs):
        raise AssertionError("explicit navigation commands must not call the LLM")

    monkeypatch.setattr(main, "call_chat_completion", fail_if_called)
    parsed = main.asyncio.run(main.parse_route_text_with_llm("带我去南开大学图书馆"))

    assert parsed["intent"] == "route"
    assert parsed["destination_text"] == "南开大学图书馆"
    assert parsed["provider"] == "rule"


def test_risk_aware_route_does_not_wait_for_llm(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fast_route.db")
    main.init_db()

    async def fake_resolve(_request):
        return 39.9, 116.4, 39.901, 116.401, {
            "origin": {"source": "request_coordinate", "coordsys": "amap"},
            "destination": {"source": "request_coordinate", "coordsys": "amap"},
        }

    async def fake_convert(lat, lng, _coordsys):
        return lat, lng

    route = {
        "input": {
            "origin": {"lat": 39.9, "lng": 116.4, "coordsys": "amap"},
            "destination": {"lat": 39.901, "lng": 116.401, "coordsys": "amap"},
        },
        "amap_origin": {"lat": 39.9, "lng": 116.4},
        "amap_destination": {"lat": 39.901, "lng": 116.401},
    }
    best = {
        "index": 0,
        "distance_m": 150,
        "duration_s": 120,
        "risk_score": 0.0,
        "risk": {},
        "steps": [],
        "polyline": [],
    }

    async def fake_plan(*args, **kwargs):
        assert kwargs["origin_amap"] == (39.9, 116.4)
        assert kwargs["destination_amap"] == (39.901, 116.401)
        return route

    async def fake_enrich(*args, **kwargs):
        return {
            "routes": [best],
            "best_route": best,
            "shortest_route": best,
            "selected_route_index": 0,
            "voice_prompt": "导航开始，请按提示前进",
        }

    async def fail_if_called(*args, **kwargs):
        raise AssertionError("route response must not call the LLM")

    monkeypatch.setattr(main, "resolve_route_endpoint", fake_resolve)
    monkeypatch.setattr(main, "convert_to_amap_coord", fake_convert)
    monkeypatch.setattr(main, "plan_walking_route", fake_plan)
    monkeypatch.setattr(main, "enrich_walking_route", fake_enrich)
    monkeypatch.setattr(main, "generate_route_advice", fail_if_called)

    result = main.asyncio.run(main.risk_aware_route(main.MapRouteRequest(
        device_id="cane_real",
        origin_lat=39.9,
        origin_lng=116.4,
        destination_lat=39.901,
        destination_lng=116.401,
        coordsys="amap",
    )))

    assert result["navigation_status"] == "ready"
    assert result["llm_advice"]["provider"] == "rule"
    assert result["llm_advice"]["skipped"] == "speed_first"


def test_route_overview_speaks_total_distance_and_cardinal_segments():
    route = {
        "distance_m": 430,
        "steps": [
            {"orientation": "北", "distance": "120", "polyline": "117.0,39.0;117.0,39.001"},
            {"orientation": "北", "distance": "80", "polyline": "117.0,39.001;117.0,39.002"},
            {"orientation": "东", "distance": "230", "polyline": "117.0,39.002;117.003,39.002"},
        ],
    }

    assert main.route_cardinal_segments(route) == [
        {"direction": "北", "distance_m": 200},
        {"direction": "东", "distance_m": 230},
    ]
    assert main.route_voice_prompt(route) == "全程430米，先向北走200米，再向东走230米"


def test_route_overview_derives_cardinal_direction_from_polyline():
    route = {
        "distance_m": 100,
        "steps": [{"distance": "100", "polyline": "117.0,39.0;117.001,39.0"}],
    }

    assert main.route_cardinal_segments(route)[0]["direction"] == "东"


def test_crosswalk_warning_is_attached_to_approach_and_crossing_steps():
    route = {
        "steps": [
            {"road": "甲路", "polyline": "117.0,39.0;117.0,39.001"},
            {
                "road": "乙路", "walk_type": "1", "instruction": "通过人行横道",
                "polyline": "117.0,39.001;117.001,39.001",
            },
        ],
    }

    warnings = main.annotate_route_crossings(route)

    assert warnings[0]["type"] == "crosswalk"
    assert route["steps"][0]["crossing_type"] == "crosswalk"
    assert route["steps"][0]["crossing_warning_id"] == route["steps"][1]["crossing_warning_id"]
    assert route["steps"][0]["crossing_lat"] == 39.001
    assert route["steps"][0]["crossing_lng"] == 117.0


def test_named_road_change_uses_fast_signalized_crosswalk_fallback():
    route = {
        "steps": [
            {"road": "甲路", "polyline": "117.0,39.0;117.0,39.001"},
            {"road": "乙路", "polyline": "117.0,39.001;117.001,39.001"},
        ],
    }

    warnings = main.annotate_route_crossings(route)

    assert warnings[0]["type"] == "crosswalk"
    assert route["steps"][0]["crossing_type"] == "crosswalk"
    assert route["steps"][1]["crossing_type"] == "crosswalk"


def test_amap_traffic_light_field_is_treated_as_crosswalk_without_extra_request():
    route = {
        "steps": [
            {
                "road": "甲路", "traffic_lights": "1",
                "polyline": "117.0,39.0;117.0,39.001",
            },
        ],
    }

    warnings = main.annotate_route_crossings(route)

    assert warnings[0]["type"] == "crosswalk"
    assert route["steps"][0]["crossing_type"] == "crosswalk"


def test_turn_between_unnamed_paths_is_annotated_as_intersection():
    route = {
        "steps": [
            {"road": "", "instruction": "向东步行80米左转", "polyline": "117.0,39.0;117.001,39.0"},
            {"road": "", "instruction": "向北步行50米", "polyline": "117.001,39.0;117.001,39.001"},
        ],
    }

    warnings = main.annotate_route_crossings(route)

    assert warnings[0]["type"] == "intersection"
    assert route["steps"][0]["crossing_type"] == "intersection"
    assert route["steps"][1]["crossing_type"] == "intersection"


def test_navigation_update_returns_distance_to_crossing(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "crossing_distance.db")
    main.init_db()
    best_route = {
        "polyline": [
            {"lat": 31.0, "lng": 121.0},
            {"lat": 31.001, "lng": 121.0},
            {"lat": 31.001, "lng": 121.001},
        ],
        "steps": [
            {"road": "甲路", "polyline": "121.0,31.0;121.0,31.001"},
            {
                "road": "乙路", "instruction": "通过斑马线",
                "polyline": "121.0,31.001;121.001,31.001",
            },
        ],
    }
    main.annotate_route_crossings(best_route)
    sid = main.create_navigation_session(
        "cane_real",
        None,
        {
            "origin": {"lat": 31.0, "lng": 121.0},
            "destination": {"lat": 31.001, "lng": 121.001},
            "best_route": best_route,
        },
        "终点",
    )

    result = main.update_navigation_session(
        sid, main.NavigationSessionUpdate(lat=31.0008, lng=121.0, distance_delta_m=3.0)
    )

    assert result["current_step"]["crossing_type"] == "crosswalk"
    assert 15 <= result["distance_to_crossing_warning_m"] <= 30
    assert result["distance_to_traffic_warning_m"] is None


def test_busy_traffic_is_attached_only_to_crossing_approach(monkeypatch):
    route = {
        "polyline": [
            {"lat": 39.0, "lng": 117.0},
            {"lat": 39.001, "lng": 117.0},
            {"lat": 39.001, "lng": 117.001},
        ],
        "steps": [
            {"road": "甲路", "distance": "100", "polyline": "117.0,39.0;117.0,39.001"},
            {
                "road": "乙路", "distance": "100", "walk_type": "1",
                "instruction": "通过人行横道", "polyline": "117.0,39.001;117.001,39.001",
            },
        ],
    }

    async def fake_amap_get(*args, **kwargs):
        return {
            "status": "1",
            "trafficinfo": {
                "roads": [{
                    "name": "乙路", "status": "3",
                    "polyline": "117.0,39.001;117.001,39.001",
                }]
            },
        }

    monkeypatch.setattr(main, "amap_get", fake_amap_get)
    traffic = main.asyncio.run(main.enrich_route_traffic(route))

    assert traffic["status"] == "available"
    assert traffic["warnings"][0]["step_index"] == 0
    assert route["steps"][0]["traffic_status"] == "拥堵"
    assert route["steps"][0]["traffic_warning"] == "前方路口车流较大"
    assert route["steps"][0]["traffic_warning_id"] == route["steps"][1]["traffic_warning_id"]


def test_traffic_failure_does_not_block_route(monkeypatch):
    async def unavailable(*args, **kwargs):
        raise main.HTTPException(status_code=502, detail="NO_PRIVILEGES")

    monkeypatch.setattr(main, "amap_get", unavailable)
    traffic = main.asyncio.run(main.enrich_route_traffic({
        "polyline": [{"lat": 39.0, "lng": 117.0}, {"lat": 39.001, "lng": 117.0}],
        "steps": [{"road": "甲路", "distance": "100", "polyline": "117.0,39.0;117.0,39.001"}],
    }))

    assert traffic["status"] == "unavailable"
    assert traffic["warnings"] == []


def test_recent_self_obstacle_is_not_rebroadcast_as_history(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "self_suppress.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="front_obstacle",
        risk_level="high",
        front_cm=25,
        distance_mm=250,
        extra_json={"source": "esp32c5"},
    ))
    warning = main.nearby_risk_warning(lat=31.0, lng=121.00002, radius=50, min_level="medium", exclude_device_id="cane_real", bearing_deg=None)
    assert warning["found"] is False


def test_test_source_is_filtered_from_active_risk_points(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "test_data_filter.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_real",
        lat=31.0,
        lng=121.0,
        risk_type="ground_drop",
        risk_level="medium",
        extra_json={"source": "android_frontend_sim"},
    ))
    assert main.active_risk_points(31.0, 121.0, radius=50, limit=10) == []
