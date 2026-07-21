"""长驻 Agent 运行时:快通道、记忆、上下文、多模型与结果记录的统一编排。"""
from __future__ import annotations

import threading
import time

import agent_registry
import brain
import llm_gateway
import skills
from context_engine import build_context
from llm import llm_reply
from memory_store import MemoryStore
from models import Reply, Utterance


class AgentRuntime:
    def __init__(self, memory: MemoryStore | None = None):
        self.memory = memory or MemoryStore()
        self.started_at = time.time()
        self.turns = 0
        self.failures = 0
        self.last_latency_ms = 0
        self._lock = threading.Lock()

    def process(self, utterance: Utterance) -> Reply:
        started = time.perf_counter()
        context = utterance.context if isinstance(utterance.context, dict) else {}
        profile = context.get("profile") if isinstance(context.get("profile"), dict) else None
        self.memory.absorb_profile(utterance.user_id, profile)
        self.memory.extract_facts(utterance.user_id, utterance.text)
        dynamic = build_context(self.memory, utterance.user_id, utterance.text, context)
        self.memory.record_turn(utterance.user_id, "user", utterance.text)

        try:
            reply = skills.match(utterance)
            if reply is None:
                reply = agent_registry.answer(utterance.text)
            if reply is None:
                reply = brain.understand(
                    utterance.text,
                    user_id=utterance.user_id,
                    profile=dynamic.get("profile"),
                    scene=dynamic.get("scene", "voice_chat"),
                    runtime_context=dynamic,
                )
            if reply is None:
                reply = llm_reply(utterance, runtime_context=dynamic)
        except Exception:
            with self._lock:
                self.failures += 1
            reply = llm_reply(utterance, runtime_context=dynamic)

        self.memory.record_turn(utterance.user_id, "assistant", reply.speech)
        latency = int((time.perf_counter() - started) * 1000)
        with self._lock:
            self.turns += 1
            self.last_latency_ms = latency
        return reply

    def status(self) -> dict:
        with self._lock:
            turns = self.turns
            failures = self.failures
            latency = self.last_latency_ms
        return {
            "running": True,
            "uptime_seconds": int(time.time() - self.started_at),
            "turns": turns,
            "failures": failures,
            "last_latency_ms": latency,
            "memory": self.memory.stats(),
            "models": llm_gateway.provider_status(),
        }


runtime = AgentRuntime()
