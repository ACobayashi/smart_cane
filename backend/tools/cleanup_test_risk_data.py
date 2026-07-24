#!/usr/bin/env python3
import argparse
import sqlite3
from pathlib import Path


TEST_KEYWORDS = ("mock", "simulator", "simulation", "fake", "demo", "test")
TEST_SOURCE_KEYWORDS = ("android_frontend_sim", "mock", "simulator", "simulation", "fake", "demo", "test")


def looks_test_text(value: str | None) -> bool:
    text = (value or "").strip().lower()
    if not text:
        return False
    return any(keyword in text for keyword in TEST_KEYWORDS)


def has_table(conn: sqlite3.Connection, table_name: str) -> bool:
    row = conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def main() -> None:
    parser = argparse.ArgumentParser(description="Clean SmartCane test and simulated data from SQLite.")
    parser.add_argument("--db", default=str(Path(__file__).resolve().parents[1] / "smartcane.db"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    conn = sqlite3.connect(args.db)
    conn.row_factory = sqlite3.Row
    try:
        risk_events = conn.execute("SELECT id, device_id, extra_json FROM risk_events").fetchall() if has_table(conn, "risk_events") else []
        remove_event_ids = []
        for row in risk_events:
            extra = str(row["extra_json"] or "").lower()
            if looks_test_text(row["device_id"]) or any(keyword in extra for keyword in TEST_SOURCE_KEYWORDS):
                remove_event_ids.append(int(row["id"]))

        device_locations = (
            conn.execute("SELECT id, device_id, source, provider, quality FROM device_locations").fetchall()
            if has_table(conn, "device_locations")
            else []
        )
        remove_location_ids = [
            int(row["id"])
            for row in device_locations
            if looks_test_text(row["device_id"])
            or any(looks_test_text(str(row[key] or "")) for key in ("source", "provider", "quality"))
        ]

        device_states = conn.execute("SELECT device_id, source FROM device_state").fetchall() if has_table(conn, "device_state") else []
        remove_state_ids = [
            str(row["device_id"])
            for row in device_states
            if looks_test_text(row["device_id"]) or looks_test_text(str(row["source"] or ""))
        ]

        risk_points = (
            conn.execute("SELECT id, source_devices_json, latest_event_id FROM risk_points").fetchall()
            if has_table(conn, "risk_points")
            else []
        )
        event_id_set = set(remove_event_ids)
        remove_point_ids = []
        for row in risk_points:
            source_devices = str(row["source_devices_json"] or "").lower()
            if any(keyword in source_devices for keyword in TEST_KEYWORDS) or int(row["latest_event_id"] or 0) in event_id_set:
                remove_point_ids.append(int(row["id"]))

        print(
            {
                "db": args.db,
                "risk_events": len(remove_event_ids),
                "device_locations": len(remove_location_ids),
                "device_state": len(remove_state_ids),
                "risk_points": len(remove_point_ids),
                "dry_run": args.dry_run,
            }
        )

        if args.dry_run:
            return

        if remove_point_ids:
            conn.executemany("DELETE FROM risk_points WHERE id = ?", [(item,) for item in remove_point_ids])
        if remove_event_ids:
            conn.executemany("DELETE FROM risk_events WHERE id = ?", [(item,) for item in remove_event_ids])
        if remove_location_ids:
            conn.executemany("DELETE FROM device_locations WHERE id = ?", [(item,) for item in remove_location_ids])
        if remove_state_ids:
            conn.executemany("DELETE FROM device_state WHERE device_id = ?", [(item,) for item in remove_state_ids])
        conn.commit()
        print("cleanup complete")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
