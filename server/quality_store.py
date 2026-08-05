"""Privacy-minimal daily quality metrics for controlled product iteration."""
from __future__ import annotations

import os
import sqlite3
from datetime import date, timedelta
from pathlib import Path


ALLOWED_EVENTS = {
    "asr_success",
    "asr_miss",
    "barge_in_legacy",
    "barge_in_realtime",
    "action_success",
    "action_failure",
}


def _default_path() -> Path:
    configured = os.getenv("XL_QUALITY_DB", "").strip()
    if configured:
        return Path(configured)
    return Path(__file__).resolve().parent / "data" / "quality.sqlite3"


class QualityStore:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path else _default_path()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS quality_daily (
                    day TEXT NOT NULL,
                    event TEXT NOT NULL,
                    count INTEGER NOT NULL DEFAULT 0,
                    success_count INTEGER NOT NULL DEFAULT 0,
                    total_latency_ms INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (day, event)
                )
            """)

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=2.0)
        db.execute("PRAGMA journal_mode=WAL")
        return db

    def record(self, event: str, latency_ms: int = 0, success: bool = True) -> bool:
        if event not in ALLOWED_EVENTS:
            return False
        latency = max(0, min(int(latency_ms), 60_000))
        with self._connect() as db:
            db.execute(
                """
                INSERT INTO quality_daily(day, event, count, success_count, total_latency_ms)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(day, event) DO UPDATE SET
                    count = count + 1,
                    success_count = success_count + excluded.success_count,
                    total_latency_ms = total_latency_ms + excluded.total_latency_ms
                """,
                (date.today().isoformat(), event, 1 if success else 0, latency),
            )
        return True

    def stats(self, days: int = 7) -> dict:
        since = (date.today() - timedelta(days=max(1, min(days, 90)) - 1)).isoformat()
        with self._connect() as db:
            rows = db.execute(
                """
                SELECT event, SUM(count), SUM(success_count), SUM(total_latency_ms)
                FROM quality_daily WHERE day >= ? GROUP BY event ORDER BY event
                """,
                (since,),
            ).fetchall()
        events = {}
        for event, count, successes, total_latency in rows:
            events[event] = {
                "count": int(count),
                "success_rate": round(int(successes) / int(count), 4) if count else 0.0,
                "average_latency_ms": round(int(total_latency) / int(count)) if count else 0,
            }
        return {"days": max(1, min(days, 90)), "events": events}


store = QualityStore()
