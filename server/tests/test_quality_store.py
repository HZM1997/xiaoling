import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from quality_store import QualityStore


def test_quality_metrics_are_aggregated_without_conversation_content(tmp_path):
    store = QualityStore(tmp_path / "quality.sqlite3")
    assert store.record("barge_in_legacy", latency_ms=120, success=True)
    assert store.record("barge_in_legacy", latency_ms=80, success=False)
    assert not store.record("raw_transcript", latency_ms=1, success=True)

    metric = store.stats()["events"]["barge_in_legacy"]
    assert metric == {"count": 2, "success_rate": 0.5, "average_latency_ms": 100}
