import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from agent_registry import status
from identity import valid_chinese_id


def test_chinese_id_checksum():
    assert valid_chinese_id("11010519491231002X")
    assert not valid_chinese_id("110105194912310021")


def test_builtin_agent_catalog_is_available():
    catalog = status()
    assert catalog["count"] >= 5
    assert all(item["endpoint"] == "builtin" for item in catalog["capabilities"])
