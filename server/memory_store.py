"""轻量持久记忆:标准库 SQLite,不把模型或数据库打进 APK。"""
from __future__ import annotations

import os
import re
import sqlite3
import time
from pathlib import Path


_SENSITIVE = re.compile(
    r"(?:身份证|银行卡|验证码|支付密码|登录密码|CVV|安全码)|"
    r"(?:\b\d{16,19}\b)|(?:\b\d{17}[\dXx]\b)"
)
_STOP_CHARS = set("的了呢啊呀吧吗和是我你他她它这个那个一下请帮给想要再就都在有说")


def _default_path() -> Path:
    explicit = os.getenv("XL_MEMORY_DB", "").strip()
    if explicit:
        return Path(explicit)
    root = Path(os.getenv("XL_DATA_DIR", Path(__file__).resolve().parent / "data"))
    return root / "memory.sqlite3"


class MemoryStore:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path) if path else _default_path()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(str(self.path), timeout=3.0)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=3000")
        return db

    def _init_schema(self) -> None:
        with self._connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    memory_key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    salience REAL NOT NULL DEFAULT 0.5,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    expires_at REAL,
                    UNIQUE(user_id, kind, memory_key)
                );
                CREATE INDEX IF NOT EXISTS idx_memories_user
                    ON memories(user_id, updated_at DESC);
                CREATE TABLE IF NOT EXISTS conversation_turns (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_turns_user
                    ON conversation_turns(user_id, id DESC);
                """
            )

    def remember(
        self,
        user_id: str,
        kind: str,
        key: str,
        value: str,
        salience: float = 0.6,
        expires_at: float | None = None,
    ) -> bool:
        value = self._safe_text(value, 120)
        if not value:
            return False
        now = time.time()
        with self._connect() as db:
            db.execute(
                """
                INSERT INTO memories
                    (user_id, kind, memory_key, value, salience, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id, kind, memory_key) DO UPDATE SET
                    value=excluded.value,
                    salience=MAX(memories.salience, excluded.salience),
                    updated_at=excluded.updated_at,
                    expires_at=excluded.expires_at
                """,
                (user_id[:64], kind[:32], key[:64], value, salience, now, now, expires_at),
            )
        return True

    def absorb_profile(self, user_id: str, profile: dict | None) -> None:
        if not isinstance(profile, dict):
            return
        for key in ("name", "prefs", "contacts"):
            value = profile.get(key)
            if value not in (None, "", [], {}):
                self.remember(user_id, "profile", key, str(value), 0.9)

    def extract_facts(self, user_id: str, text: str) -> list[dict]:
        if _SENSITIVE.search(text):
            return []
        patterns = (
            ("profile", "name", r"(?:我叫|叫我)([\u4e00-\u9fff]{2,8})", 0.95),
            ("preference", "likes", r"我(?:喜欢|爱听|爱看|爱吃)([^,，。！？!?]{1,28})", 0.78),
            ("family", "relation", r"我(女儿|儿子|老伴|丈夫|妻子|孙子|孙女)叫([\u4e00-\u9fff]{2,8})", 0.9),
            ("region", "home", r"我(?:住在|家在)([^,，。！？!?]{2,24})", 0.65),
        )
        found: list[dict] = []
        for kind, key, pattern, salience in patterns:
            match = re.search(pattern, text)
            if not match:
                continue
            value = "".join(match.groups()) if len(match.groups()) > 1 else match.group(1)
            if self.remember(user_id, kind, key, value, salience):
                found.append({"kind": kind, "key": key, "value": value})
        return found

    def record_turn(self, user_id: str, role: str, content: str) -> None:
        clean = self._safe_text(content, 600, redact=True)
        if not clean:
            return
        with self._connect() as db:
            db.execute(
                "INSERT INTO conversation_turns(user_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                (user_id[:64], role if role in {"user", "assistant"} else "user", clean, time.time()),
            )
            db.execute(
                """
                DELETE FROM conversation_turns
                WHERE user_id=? AND id NOT IN (
                    SELECT id FROM conversation_turns WHERE user_id=? ORDER BY id DESC LIMIT 40
                )
                """,
                (user_id[:64], user_id[:64]),
            )

    def recent_turns(self, user_id: str, limit: int = 6) -> list[dict]:
        with self._connect() as db:
            rows = db.execute(
                "SELECT role, content, created_at FROM conversation_turns "
                "WHERE user_id=? ORDER BY id DESC LIMIT ?",
                (user_id[:64], max(1, min(limit, 12))),
            ).fetchall()
        return [dict(row) for row in reversed(rows)]

    def recall(self, user_id: str, query: str, limit: int = 6) -> list[dict]:
        now = time.time()
        with self._connect() as db:
            rows = db.execute(
                """
                SELECT kind, memory_key, value, salience, updated_at
                FROM memories
                WHERE user_id=? AND (expires_at IS NULL OR expires_at>?)
                ORDER BY updated_at DESC LIMIT 60
                """,
                (user_id[:64], now),
            ).fetchall()
        query_chars = self._meaningful_chars(query)
        ranked = []
        for row in rows:
            item = dict(row)
            value_chars = self._meaningful_chars(item["value"] + item["memory_key"])
            overlap = len(query_chars & value_chars) / max(1, len(query_chars | value_chars))
            age_days = max(0.0, (now - item["updated_at"]) / 86400)
            score = overlap * 2.2 + float(item["salience"]) + 0.25 / (1.0 + age_days)
            item["score"] = round(score, 3)
            ranked.append(item)
        ranked.sort(key=lambda item: item["score"], reverse=True)
        return ranked[: max(1, min(limit, 12))]

    def stats(self) -> dict:
        with self._connect() as db:
            memories = db.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
            turns = db.execute("SELECT COUNT(*) FROM conversation_turns").fetchone()[0]
        return {"backend": "sqlite", "memories": memories, "turns": turns}

    def forget(self, user_id: str) -> None:
        with self._connect() as db:
            db.execute("DELETE FROM memories WHERE user_id=?", (user_id[:64],))
            db.execute("DELETE FROM conversation_turns WHERE user_id=?", (user_id[:64],))

    @staticmethod
    def _meaningful_chars(text: str) -> set[str]:
        return {ch for ch in text.lower() if ch.isalnum() and ch not in _STOP_CHARS}

    @staticmethod
    def _safe_text(text: str, limit: int, redact: bool = False) -> str:
        value = re.sub(r"\s+", " ", str(text)).strip()[:limit]
        if not value:
            return ""
        if _SENSITIVE.search(value):
            return "[敏感内容已省略]" if redact else ""
        return value
