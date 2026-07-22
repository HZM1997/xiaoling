import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import asr_gateway


def test_asr_requires_key(monkeypatch):
    monkeypatch.delenv("XL_ASR_KEY", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    assert not asr_gateway.available()


def test_asr_posts_wav_to_compatible_endpoint(monkeypatch):
    monkeypatch.setenv("XL_ASR_KEY", "test-key")
    monkeypatch.setenv("XL_ASR_BASE_URL", "https://asr.example/v1")
    captured = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def read(self, _limit):
            return '{"text":"今天天气不错"}'.encode("utf-8")

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["body"] = request.data
        captured["timeout"] = timeout
        return Response()

    monkeypatch.setattr(asr_gateway.urllib.request, "urlopen", fake_urlopen)
    text = asr_gateway.transcribe(b"RIFF-test-audio")
    assert text == "今天天气不错"
    assert captured["url"] == "https://asr.example/v1/audio/transcriptions"
    assert b"speech.wav" in captured["body"]
