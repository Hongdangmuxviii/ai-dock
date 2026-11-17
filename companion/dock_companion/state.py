"""사용량/미디어 상태 저장소. 바뀔 때마다 구독자(웹소켓 클라이언트)에게 알립니다."""
from __future__ import annotations

import asyncio
import json
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Awaitable, Callable, Optional

from .config import DATA_DIR


@dataclass
class UsageWindow:
    key: str              # "five_hour", "weekly", "daily" 등
    label: str            # 화면에 보일 이름
    used_percent: float   # 0~100
    resets_at: Optional[float] = None  # epoch 초


@dataclass
class ProviderUsage:
    id: str                    # "claude" | "codex" | "gemini"
    name: str
    status: str = "unknown"    # ok | stale | error | unavailable | unknown
    source: str = ""           # 값을 어디서 가져왔는지 (statusline, app-server, ...)
    updated_at: Optional[float] = None
    windows: list[UsageWindow] = field(default_factory=list)
    message: str = ""

    def to_json(self) -> dict[str, Any]:
        return asdict(self)


Listener = Callable[[dict[str, Any]], Awaitable[None]]


ALL_PROVIDERS = {
    "claude": "Claude Code",
    "codex": "Codex",
    "gemini": "Gemini CLI",
}


class Hub:
    def __init__(self, enabled: Optional[list[str]] = None) -> None:
        # 꺼둔 도구(config 의 *_enabled=false)는 목록에서 아예 빼서 기기 화면에 카드가 안 생기게 합니다.
        ids = [pid for pid in ALL_PROVIDERS if enabled is None or pid in enabled]
        self.providers: dict[str, ProviderUsage] = {
            pid: ProviderUsage(pid, ALL_PROVIDERS[pid]) for pid in ids
        }
        self.media: dict[str, Any] = {"available": False}
        self.thumbnail: Optional[dict[str, str]] = None
        self.background: Optional[dict[str, str]] = None
        self._listeners: set[Listener] = set()
        self._cache_path = DATA_DIR / "usage_cache.json"
        self._load_cache()

    # ---- 구독 ----
    def subscribe(self, fn: Listener) -> None:
        self._listeners.add(fn)

    def unsubscribe(self, fn: Listener) -> None:
        self._listeners.discard(fn)

    async def broadcast(self, msg: dict[str, Any]) -> None:
        for fn in list(self._listeners):
            try:
                await fn(msg)
            except Exception:
                self._listeners.discard(fn)

    # ---- 사용량 ----
    def usage_message(self) -> dict[str, Any]:
        return {
            "type": "usage",
            "server_time": time.time(),
            "providers": [p.to_json() for p in self.providers.values()],
        }

    async def set_usage(self, pid: str, *, windows: list[UsageWindow], source: str,
                        status: str = "ok", message: str = "") -> None:
        p = self.providers[pid]
        p.windows = windows
        p.source = source
        p.status = status
        p.message = message
        p.updated_at = time.time()
        self._save_cache()
        await self.broadcast(self.usage_message())

    async def set_status(self, pid: str, status: str, message: str = "") -> None:
        """값은 유지한 채 상태만 바꿉니다(예: 조회 실패 → 마지막 값을 stale로 표시)."""
        p = self.providers[pid]
        if p.windows and status in ("error", "unavailable"):
            status = "stale"
        if p.status == status and p.message == message:
            return
        p.status = status
        p.message = message
        await self.broadcast(self.usage_message())

    # ---- 미디어 ----
    async def set_media(self, media: dict[str, Any]) -> None:
        if media == self.media:
            return
        self.media = media
        await self.broadcast({"type": "media", **media})

    async def set_thumbnail(self, thumb: Optional[dict[str, str]]) -> None:
        self.thumbnail = thumb
        await self.broadcast({"type": "thumbnail", "image": thumb})

    # ---- 배경 이미지 ----
    async def set_background(self, image: Optional[dict[str, str]]) -> None:
        self.background = image
        await self.broadcast({"type": "background", "image": image})

    # ---- 캐시 ----
    def _save_cache(self) -> None:
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            data = {k: v.to_json() for k, v in self.providers.items()}
            self._cache_path.write_text(json.dumps(data), encoding="utf-8")
        except OSError:
            pass

    def _load_cache(self) -> None:
        try:
            data = json.loads(self._cache_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        for pid, raw in data.items():
            if pid not in self.providers:
                continue
            p = self.providers[pid]
            p.windows = [UsageWindow(**w) for w in raw.get("windows", [])]
            p.source = raw.get("source", "")
            p.updated_at = raw.get("updated_at")
            p.status = "stale" if p.windows else "unknown"


async def sleep_or_wake(event: asyncio.Event, seconds: float) -> None:
    """seconds 동안 기다리되, 수동 새로고침(event)이 오면 바로 깹니다."""
    try:
        await asyncio.wait_for(event.wait(), timeout=seconds)
    except asyncio.TimeoutError:
        pass
    event.clear()
