"""官方预警数据源适配框架。

气象主管部门授权源通过 OFFICIAL_WEATHER_ALERT_URL 配置。未配置时明确返回空,
不生成模拟灾害数据。Android 端另直接接入 USGS 官方地震公开接口。
"""
from __future__ import annotations

import json
import os
import urllib.request
from dataclasses import dataclass, asdict
from typing import Protocol


@dataclass
class Alert:
    id: str
    category: str
    speech: str
    source: str
    url: str = ""


class OfficialAlertProvider(Protocol):
    def fetch(self, lat: float | None, lon: float | None) -> list[Alert]: ...


class ConfiguredJsonProvider:
    """适配返回 {alerts:[...]} 的获授权官方 JSON 源。"""
    def __init__(self, endpoint: str):
        self.endpoint = endpoint

    def fetch(self, lat: float | None, lon: float | None) -> list[Alert]:
        separator = "&" if "?" in self.endpoint else "?"
        endpoint = self.endpoint
        if lat is not None and lon is not None:
            endpoint += f"{separator}lat={lat}&lon={lon}"
        request = urllib.request.Request(endpoint, headers={"Accept": "application/json", "User-Agent": "Xiaoling-Server/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=2.5) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except Exception:
            return []
        result: list[Alert] = []
        for item in payload.get("alerts", [])[:20]:
            alert_id = str(item.get("id", "")).strip()
            speech = str(item.get("speech", "")).strip()
            category = str(item.get("category", "weather")).strip()
            if not alert_id or not speech or category not in {"typhoon", "rainstorm", "sandstorm", "weather", "earthquake"}:
                continue
            result.append(Alert(alert_id, category, speech,
                                str(item.get("source", "官方预警源")), str(item.get("url", ""))))
        return result


def collect_alerts(lat: float | None = None, lon: float | None = None) -> list[dict]:
    providers: list[OfficialAlertProvider] = []
    endpoint = os.getenv("OFFICIAL_WEATHER_ALERT_URL", "").strip()
    if endpoint:
        providers.append(ConfiguredJsonProvider(endpoint))
    alerts: list[Alert] = []
    for provider in providers:
        alerts.extend(provider.fetch(lat, lon))
    return [asdict(alert) for alert in alerts]
