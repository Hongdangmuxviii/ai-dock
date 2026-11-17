"""Codex 사용량.

1순위: `codex app-server` (Codex CLI의 공식 JSON-RPC 인터페이스, stdio)
       - account/rateLimits/read 로 조회
       - account/rateLimits/updated 알림으로 실시간 갱신
2순위: chatgpt.com/backend-api/wham/usage (비공식). codex 실행 파일을 못 찾거나
       app-server가 실패할 때만 씁니다. 토큰은 읽기만 하고 갱신하지 않습니다.
"""
from __future__ import annotations

import asyncio
import itertools
import json
import logging
import os
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, Optional

import aiohttp

from ..config import Config
from ..state import Hub, UsageWindow, sleep_or_wake
from ..util import first, http_session, to_epoch, to_percent

log = logging.getLogger("aidock.codex")

WHAM_URL = "https://chatgpt.com/backend-api/wham/usage"


def _label(minutes: Optional[float], fallback: str) -> tuple[str, str]:
    if not minutes:
        return fallback, fallback
    if minutes <= 6 * 60:
        return "five_hour", f"{round(minutes / 60)}시간"
    if minutes >= 6 * 24 * 60:
        return "weekly", "주간"
    return f"w{int(minutes)}", f"{round(minutes / 60)}시간"


def parse_app_server_limits(result: dict[str, Any]) -> list[UsageWindow]:
    """account/rateLimits/read 결과 또는 updated 알림의 params."""
    rl = result.get("rateLimits") or result
    out: list[UsageWindow] = []
    for slot in ("primary", "secondary"):
        w = rl.get(slot)
        if not isinstance(w, dict):
            continue
        pct = to_percent(first(w, "usedPercent", "used_percent"))
        if pct is None:
            continue
        key, label = _label(first(w, "windowDurationMins", "window_minutes"), slot)
        out.append(UsageWindow(key=key, label=label, used_percent=pct,
                               resets_at=to_epoch(first(w, "resetsAt", "resets_at"))))
    return out


def parse_wham(data: dict[str, Any]) -> list[UsageWindow]:
    rl = data.get("rate_limit") or {}
    out: list[UsageWindow] = []
    now = time.time()
    for slot in ("primary_window", "secondary_window"):
        w = rl.get(slot)
        if not isinstance(w, dict):
            continue
        pct = to_percent(first(w, "used_percent", "usedPercent"))
        if pct is None:
            continue
        secs = first(w, "limit_window_seconds")
        key, label = _label(secs / 60 if secs else None, slot)
        reset = to_epoch(first(w, "reset_at", "resets_at"))
        if reset is None and first(w, "reset_after_seconds") is not None:
            reset = now + float(w["reset_after_seconds"])
        out.append(UsageWindow(key=key, label=label, used_percent=pct, resets_at=reset))
    return out


def _codex_home() -> Path:
    return Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))


class AppServer:
    """codex app-server 프로세스 하나를 붙잡고 JSON-RPC로 대화합니다."""

    def __init__(self, exe: str, on_notify) -> None:
        self.exe = exe
        self.on_notify = on_notify
        self.proc: Optional[asyncio.subprocess.Process] = None
        self._ids = itertools.count(1)
        self._pending: dict[int, asyncio.Future] = {}
        self._reader: Optional[asyncio.Task] = None

    @property
    def alive(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    async def start(self) -> None:
        self.proc = await asyncio.create_subprocess_exec(
            self.exe, "app-server",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            limit=4 * 1024 * 1024,
            # 콘솔 없이(pythonw) 실행 중일 때 codex 콘솔 창이 뜨지 않게
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self._reader = asyncio.create_task(self._read_loop())
        await self.request("initialize", {
            "clientInfo": {"name": "aidock", "title": "AI Dock", "version": "0.1.0"},
        })
        await self._send({"method": "initialized"})

    async def stop(self) -> None:
        if self.alive:
            self.proc.terminate()
            try:
                await asyncio.wait_for(self.proc.wait(), 5)
            except asyncio.TimeoutError:
                self.proc.kill()
        if self._reader:
            self._reader.cancel()

    async def _send(self, msg: dict[str, Any]) -> None:
        assert self.proc and self.proc.stdin
        self.proc.stdin.write((json.dumps(msg) + "\n").encode())
        await self.proc.stdin.drain()

    async def request(self, method: str, params: Optional[dict] = None, timeout: float = 20) -> Any:
        rid = next(self._ids)
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        msg: dict[str, Any] = {"method": method, "id": rid}
        if params is not None:
            msg["params"] = params
        await self._send(msg)
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._pending.pop(rid, None)

    async def _read_loop(self) -> None:
        assert self.proc and self.proc.stdout
        while True:
            line = await self.proc.stdout.readline()
            if not line:
                break
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if "id" in msg and ("result" in msg or "error" in msg):
                fut = self._pending.get(msg["id"])
                if fut and not fut.done():
                    if "error" in msg:
                        fut.set_exception(RuntimeError(str(msg["error"])))
                    else:
                        fut.set_result(msg["result"])
            elif "method" in msg and "id" not in msg:
                try:
                    await self.on_notify(msg["method"], msg.get("params") or {})
                except Exception:
                    log.exception("알림 처리 실패")
            elif "method" in msg and "id" in msg:
                # 서버→클라이언트 요청(승인 요청 등). 우리는 대화를 시작하지 않으니 거절만.
                await self._send({"id": msg["id"], "error": {"code": -32601, "message": "unsupported"}})
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError("app-server 종료됨"))


class CodexCollector:
    def __init__(self, cfg: Config, hub: Hub, refresh: asyncio.Event) -> None:
        self.cfg = cfg
        self.hub = hub
        self.refresh = refresh
        self.server: Optional[AppServer] = None

    def _exe(self) -> Optional[str]:
        if self.cfg.codex_path:
            return self.cfg.codex_path
        return shutil.which("codex")

    async def _on_notify(self, method: str, params: dict[str, Any]) -> None:
        if method == "account/rateLimits/updated":
            windows = parse_app_server_limits(params)
            if windows:
                await self.hub.set_usage("codex", windows=windows, source="app-server")

    async def _via_app_server(self) -> bool:
        exe = self._exe()
        if not exe:
            return False
        if not (self.server and self.server.alive):
            if self.server:
                await self.server.stop()
            self.server = AppServer(exe, self._on_notify)
            await self.server.start()
            log.info("codex app-server 연결: %s", exe)
        result = await self.server.request("account/rateLimits/read")
        windows = parse_app_server_limits(result or {})
        if not windows:
            return False
        await self.hub.set_usage("codex", windows=windows, source="app-server")
        return True

    async def _via_wham(self, session: aiohttp.ClientSession) -> bool:
        path = _codex_home() / "auth.json"
        try:
            tokens = json.loads(path.read_text(encoding="utf-8")).get("tokens") or {}
        except (OSError, ValueError):
            return False
        access, account = tokens.get("access_token"), tokens.get("account_id")
        if not access:
            return False
        headers = {"Authorization": f"Bearer {access}", "User-Agent": "aidock-companion"}
        if account:
            headers["ChatGPT-Account-Id"] = account
        async with session.get(WHAM_URL, headers=headers, timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status != 200:
                await self.hub.set_status("codex", "error", f"wham/usage HTTP {r.status}")
                return False
            data = await r.json(content_type=None)
        windows = parse_wham(data)
        if not windows:
            return False
        await self.hub.set_usage("codex", windows=windows, source="wham/usage")
        return True

    async def run(self) -> None:
        async with http_session() as session:
            while True:
                ok = False
                reason = "codex CLI를 못 찾았고 로그인 정보도 없음 (codex login 필요)"
                try:
                    ok = await self._via_app_server()
                except Exception as e:
                    log.warning("app-server 조회 실패: %s", e)
                    reason = f"app-server 실패: {e}"[:120]
                    if self.server:
                        await self.server.stop()
                        self.server = None
                if not ok:
                    try:
                        ok = await self._via_wham(session)
                    except Exception as e:
                        log.warning("wham 조회 실패: %s", e)
                        reason = f"wham/usage 실패: {e}"[:120]
                if not ok and self.hub.providers["codex"].status not in ("error", "stale"):
                    await self.hub.set_status("codex", "unavailable", reason)
                await sleep_or_wake(self.refresh, self.cfg.codex_poll_sec)
