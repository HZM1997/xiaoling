import asyncio
import hashlib
from types import SimpleNamespace

import pipecat_bridge


def test_vendored_pipecat_model_is_pinned():
    digest = hashlib.sha256(pipecat_bridge._MODEL_PATH.read_bytes()).hexdigest()
    assert digest == "597d30b3ec076608d059477bb14cfeffdf951bf5cae370d38f65d33bbfe82004"


class FakeAnalyzer:
    def __init__(self, states):
        self.states = iter(states)
        self.cleaned = False

    async def analyze_audio(self, pcm):
        assert pcm
        return SimpleNamespace(name=next(self.states))

    async def cleanup(self):
        self.cleaned = True


def test_duplex_vad_emits_only_speech_transitions():
    async def scenario():
        analyzer = FakeAnalyzer(["STARTING", "SPEAKING", "SPEAKING", "STOPPING", "QUIET"])
        vad = pipecat_bridge.DuplexVad(analyzer)

        events = [await vad.feed(b"\0\0" * 512) for _ in range(5)]

        assert events == [None, "started", None, None, "stopped"]
        await vad.close()
        assert analyzer.cleaned is True

    asyncio.run(scenario())


def test_backchannel_policy_waits_and_emits_once_per_utterance():
    policy = pipecat_bridge.BackchannelPolicy(delay_seconds=1.8)
    policy.speech_started(now=10.0)

    assert policy.take_if_due(False, now=11.7) is None
    assert policy.take_if_due(True, now=12.0) is None
    assert policy.take_if_due(False, now=12.0) == "嗯"
    assert policy.take_if_due(False, now=20.0) is None

    policy.speech_stopped()
    policy.speech_started(now=30.0)
    assert policy.take_if_due(False, now=31.8) == "我在听"


def test_real_vad_model_loads_when_onnxruntime_is_installed():
    if not pipecat_bridge.status()["available"]:
        return

    async def scenario():
        vad = pipecat_bridge.DuplexVad()
        assert vad.enabled is True
        assert await vad.feed(b"\0\0" * 640) is None
        await vad.close()

    asyncio.run(scenario())
