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


def test_down_step_acceptance_20cm_150cm_and_400_sentinel():
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
    assert run([55] * 5 + [75, 75])[-1] == "ground_step_down"
    assert run([55] * 5 + [151, 151])[-1] == "ground_step_down"
    assert run([55] * 5 + [400, 400])[-1] == "none"
    assert run([55] * 5 + [35, 35])[-1] == "none"


def test_deep_model_down_boundaries():
    history = {"risk_count": 0, "high_count": 0, "medium_count": 0, "max_level": "low"}
    for down in [20, 70, 89, 90, 91, 100, 390, None]:
        req = SimpleNamespace(risk_type="none", manual_risk_type="none", alert_type=None, fall_detected=False, front_cm=200, left_cm=120, right_cm=120, down_cm=down)
        result = score_deep_risk(req, history)
        assert result["level"] == "low", (down, result)
        assert result["score"] < 0.56, (down, result)
    req = SimpleNamespace(risk_type="ground_step_down", manual_risk_type=None, alert_type=None, fall_detected=False, front_cm=200, left_cm=120, right_cm=120, down_cm=75)
    assert score_deep_risk(req, history)["level"] == "medium"


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


def test_cancel_fall_command_is_deduplicated_and_delivered_once(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "commands.db")
    main.init_db()
    request = main.DeviceCommandCreate(device_id="cane_real", command="cancel_fall", source="android")
    first = main.create_device_command(request)
    second = main.create_device_command(request)
    assert first["command_id"] == second["command_id"]
    delivered = main.next_device_command("cane_real")
    assert delivered["command"]["command"] == "cancel_fall"
    assert main.next_device_command("cane_real")["command"] is None


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


def test_fall_suppresses_other_sensor_alerts_for_30_seconds(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "DB_PATH", tmp_path / "fall_suppression.db")
    main.init_db()
    main.store_event(main.EventCreate(
        device_id="cane_real", lat=31.0, lng=121.0,
        risk_type="fall_detected", risk_level="high",
        fall_event_id="fall-exclusive-1",
    ))
    response = main.create_sensor_frame(frame(
        55, device_id="cane_real", front_cm=10, down_raw_cm=55,
        down_valid=True, down_status="valid",
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
    assert "SMARTCANE_DOWN_DROP_DELTA_CM 20" in config
    assert "SMARTCANE_DOWN_LONG_DISTANCE_ALARM_CM 150" in config
    assert "SMARTCANE_DOWN_NO_TARGET_CM 400" in config
    assert "heightDeltaCm >= SMARTCANE_DOWN_DROP_DELTA_CM" in firmware
    assert "cm >= SMARTCANE_DOWN_NO_TARGET_CM" in firmware
    assert "fall_pending" in sketch and "fall_detected" in sketch
    assert "fallRiskSuppressUntilMs" in sketch


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
