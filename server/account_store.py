"""轻量账号持久化。生产可平滑替换为托管 PostgreSQL。"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
from pathlib import Path

_lock = threading.RLock()
_path = Path(os.getenv("ACCOUNT_DB_PATH", "xiaoling_accounts.db"))


def _connect() -> sqlite3.Connection:
    connection = sqlite3.connect(_path, timeout=5)
    connection.execute(
        "CREATE TABLE IF NOT EXISTS accounts (phone TEXT PRIMARY KEY, data TEXT NOT NULL, updated_at INTEGER NOT NULL)"
    )
    return connection


def get(phone: str) -> dict | None:
    with _lock, _connect() as connection:
        row = connection.execute("SELECT data FROM accounts WHERE phone = ?", (phone,)).fetchone()
    if row is None:
        return None
    try:
        return json.loads(row[0])
    except Exception:
        return None


def save(phone: str, data: dict) -> None:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    with _lock, _connect() as connection:
        connection.execute(
            "INSERT INTO accounts(phone,data,updated_at) VALUES(?,?,strftime('%s','now')) "
            "ON CONFLICT(phone) DO UPDATE SET data=excluded.data,updated_at=excluded.updated_at",
            (phone, payload),
        )
