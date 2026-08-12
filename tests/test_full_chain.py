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


def test_front_warns_at_105cm_and_firmware_ground_direction_is_preserved():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    assert main.analyze_sensor_frame(frame(55, front_cm=106), history)["risk_type"] == "none"
    assert main.analyze_sensor_frame(frame(55, front_cm=105), history)["risk_type"] == "front_obstacle"
    up = main.analyze_sensor_frame(frame(
        42, risk_type="ground_step", direction="up", compensated_down_cm=42,
        ground_baseline_cm=55, height_delta_cm=-13, ground_state="GROUND_STEP_UP"
    ), history)
    assert up["risk_type"] == "ground_step"
    assert up["direction"] == "up"
    assert "上台阶" in up["voice_prompt"]
    down = main.analyze_sensor_frame(frame(
        68, risk_type="ground_step", direction="down", compensated_down_cm=68,
        ground_baseline_cm=55, height_delta_cm=13, ground_state="GROUND_STEP_DOWN"
    ), history)
    assert down["risk_type"] == "ground_step"
    assert down["direction"] == "down"
    assert "下台阶" in down["voice_prompt"]
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


def test_navigation_requires_three_off_route_and_arrival_frames(tmp_path, monkeypatch):
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
    for count in range(1, 4):
        update = main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.0005, lng=121.001))
        assert update["off_route_count"] == count
        assert update["should_replan"] is (count == 3)
    for count in range(1, 4):
        update = main.update_navigation_session(sid, main.NavigationSessionUpdate(lat=31.001, lng=121.0))
        assert update["arrival_count"] == count
        assert update["arrived"] is (count == 3)


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
    assert 1799 <= ttl_seconds <= 1801

    warning = main.nearby_risk_warning(
        lat=31.0,
        lng=121.00002,
        radius=50,
        min_level="medium",
        exclude_device_id="cane_device_b",
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
    main.create_location(main.LocationCreate(
        device_id=device_id,
        lat=31.2,
        lng=121.2,
        source="android",
        provider="amap",
        quality="usable",
        timestamp=(now - main.timedelta(seconds=301)).isoformat(timespec="seconds"),
    ))
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
    imu = (ROOT / "firmware" / "smartcane_arduino" / "imu_fall.cpp").read_text(encoding="utf-8")
    assert "SMARTCANE_STEP_UP_ENTER_CM 9" in config
    assert "SMARTCANE_STEP_DOWN_ENTER_CM 11" in config
    assert "SMARTCANE_DEEP_DROP_CM 30" in config
    assert "SMARTCANE_SIDE_ALERT_CM 35" in config
    assert "SMARTCANE_DOWN_NO_TARGET_CM 400" in config
    assert "lastHeightDeltaCm >= SMARTCANE_STEP_DOWN_ENTER_CM" in firmware
    assert "lastHeightDeltaCm <= -SMARTCANE_STEP_UP_ENTER_CM" in firmware
    assert "cm > SMARTCANE_DOWN_LONG_DISTANCE_ALARM_CM" not in firmware
    assert "rawCm >= SMARTCANE_DOWN_NO_TARGET_CM" in firmware
    assert "FALL_STAGE_CANDIDATE" in imu
    assert "REG_ACC_X_LSB = 0x0C" in imu
    assert "REG_GYR_X_LSB = 0x12" in imu
    assert "readReg(REG_ACC_X_LSB, bytes, sizeof(bytes))" in imu
    assert "SMARTCANE_FALL_CONFIRM_MS 1900" in config
    assert "fall_confirmed" in sketch and "fall_detected" in sketch
    assert "fallLockActive" in sketch


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
    assert "SOS" in warning["warning"]["voicePrompt"]


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
    assert "重复出现的障碍风险点" in warning["warning"]["voicePrompt"]


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
