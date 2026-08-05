import firewall


def test_production_client_auth_fails_closed(monkeypatch):
    monkeypatch.setenv("XL_ENV", "production")
    monkeypatch.delenv("XL_CLIENT_TOKEN", raising=False)
    monkeypatch.delenv("XL_REALTIME_CLIENT_TOKEN", raising=False)
    assert firewall.token_valid({}) is False
    assert firewall.security_status()["fail_closed"] is True


def test_client_token_validation(monkeypatch):
    monkeypatch.setenv("XL_ENV", "production")
    monkeypatch.setenv("XL_CLIENT_TOKEN", "a-long-random-client-token")
    assert firewall.token_valid({"x-xiaoling-token": "a-long-random-client-token"}) is True
    assert firewall.token_valid({"x-xiaoling-token": "wrong"}) is False


def test_realtime_connection_limit(monkeypatch):
    monkeypatch.setattr(firewall, "_realtime_handshakes", firewall._Bucket())
    monkeypatch.setattr(firewall, "_realtime_active", {})
    assert firewall.acquire_realtime("192.0.2.10") is True
    assert firewall.acquire_realtime("192.0.2.10") is True
    assert firewall.acquire_realtime("192.0.2.10") is True
    assert firewall.acquire_realtime("192.0.2.10") is False
    firewall.release_realtime("192.0.2.10")
    assert firewall.acquire_realtime("192.0.2.10") is True
