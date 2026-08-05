"""Focused Pipecat Silero VAD integration for Xiaoling's realtime gateway.

The stateful ONNX inference follows Pipecat 1.7.0's Silero analyzer, pinned at
commit 65c4c3f. Only the VAD core and model are vendored so the server doesn't
pull unrelated providers; see vendor/pipecat/LICENSE.
"""
from __future__ import annotations

import asyncio
import os
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any

try:
    import numpy as np
    import onnxruntime

    _IMPORT_ERROR = ""
except Exception as exc:
    np = None
    onnxruntime = None
    _IMPORT_ERROR = exc.__class__.__name__

_MODEL_PATH = Path(__file__).resolve().parent / "vendor" / "pipecat" / "silero_vad.onnx"


def _confidence() -> float:
    try:
        value = float(os.getenv("XL_PIPECAT_VAD_CONFIDENCE", "0.46"))
    except (TypeError, ValueError):
        value = 0.46
    return max(0.25, min(value, 0.9))


def status() -> dict[str, Any]:
    available = onnxruntime is not None and _MODEL_PATH.is_file()
    return {
        "available": available,
        "framework": "pipecat-silero-core",
        "version": "1.7.0@65c4c3f",
        "vad": "silero" if available else "provider-semantic-vad",
        "error": "" if available else (_IMPORT_ERROR or "model_missing"),
    }


class _SileroOnnxModel:
    def __init__(self, path: Path):
        options = onnxruntime.SessionOptions()
        options.inter_op_num_threads = 1
        options.intra_op_num_threads = 1
        providers = ["CPUExecutionProvider"] if "CPUExecutionProvider" in onnxruntime.get_available_providers() else None
        self.session = onnxruntime.InferenceSession(str(path), providers=providers, sess_options=options)
        self.reset()

    def reset(self) -> None:
        self.state = np.zeros((2, 1, 128), dtype="float32")
        self.context = np.zeros((1, 64), dtype="float32")

    def confidence(self, pcm16: bytes) -> float:
        samples = np.frombuffer(pcm16, np.int16).astype(np.float32) / 32768.0
        samples = np.expand_dims(samples, 0)
        combined = np.concatenate((self.context, samples), axis=1)
        output, self.state = self.session.run(
            None,
            {"input": combined, "state": self.state, "sr": np.array(16_000, dtype="int64")},
        )
        self.context = combined[..., -64:]
        return float(np.asarray(output).reshape(-1)[0])


class _VendoredSileroAnalyzer:
    def __init__(self):
        self.model = _SileroOnnxModel(_MODEL_PATH)
        self.buffer = b""
        self.state = "QUIET"
        self.start_count = 0
        self.stop_count = 0
        self.last_reset = time.monotonic()
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="xiaoling-silero-vad")

    async def analyze_audio(self, pcm16: bytes) -> str:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self.executor, self._analyze, pcm16)

    def _analyze(self, pcm16: bytes) -> str:
        self.buffer += pcm16
        frame_bytes = 512 * 2
        while len(self.buffer) >= frame_bytes:
            frame, self.buffer = self.buffer[:frame_bytes], self.buffer[frame_bytes:]
            confidence = self.model.confidence(frame)
            samples = np.frombuffer(frame, np.int16).astype(np.float32) / 32768.0
            volume = float(np.sqrt(np.mean(samples * samples))) if samples.size else 0.0
            speaking = confidence >= _confidence() and volume >= 0.0015
            if speaking:
                if self.state == "QUIET":
                    self.state = "SPEAKING"
                    self.start_count = 0
                elif self.state == "STARTING":
                    self.start_count += 1
                    if self.start_count >= 2:
                        self.state = "SPEAKING"
                        self.start_count = 0
                elif self.state == "STOPPING":
                    self.state = "SPEAKING"
                    self.stop_count = 0
            else:
                if self.state == "STARTING":
                    self.state = "QUIET"
                    self.start_count = 0
                elif self.state == "SPEAKING":
                    self.state = "STOPPING"
                    self.stop_count = 1
                elif self.state == "STOPPING":
                    self.stop_count += 1
                    if self.stop_count >= 10:
                        self.state = "QUIET"
                        self.stop_count = 0
            if time.monotonic() - self.last_reset >= 5.0:
                self.model.reset()
                self.last_reset = time.monotonic()
        return self.state

    async def cleanup(self) -> None:
        self.executor.shutdown(wait=False, cancel_futures=True)


class DuplexVad:
    """Convert Pipecat-compatible VAD states into one-shot transitions."""

    def __init__(self, analyzer: Any = None):
        if analyzer is None and status()["available"]:
            analyzer = _VendoredSileroAnalyzer()
        self._analyzer = analyzer
        self._speaking = False

    @property
    def enabled(self) -> bool:
        return self._analyzer is not None

    async def feed(self, pcm16: bytes) -> str | None:
        if self._analyzer is None or not pcm16:
            return None
        state = await self._analyzer.analyze_audio(pcm16)
        name = str(getattr(state, "name", state)).upper()
        if name == "SPEAKING" and not self._speaking:
            self._speaking = True
            return "started"
        if name == "QUIET" and self._speaking:
            self._speaking = False
            return "stopped"
        return None

    async def close(self) -> None:
        if self._analyzer is not None:
            await self._analyzer.cleanup()


@dataclass
class BackchannelPolicy:
    """Make a lightweight listening decision five times per second."""

    delay_seconds: float = 1.8
    speaking_since: float = 0.0
    emitted: bool = False
    sequence: int = 0

    def speech_started(self, now: float | None = None) -> None:
        self.speaking_since = now if now is not None else time.monotonic()
        self.emitted = False

    def speech_stopped(self) -> None:
        self.speaking_since = 0.0
        self.emitted = False

    def take_if_due(self, response_active: bool, now: float | None = None) -> str | None:
        current = now if now is not None else time.monotonic()
        if response_active or self.emitted or self.speaking_since <= 0:
            return None
        if current - self.speaking_since < self.delay_seconds:
            return None
        self.emitted = True
        options = ("嗯", "我在听")
        text = options[self.sequence % len(options)]
        self.sequence += 1
        return text
