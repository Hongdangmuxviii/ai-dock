"""Claude Code 사용량.

1순위: Claude Code 상태줄(statusline)이 넘겨주는 rate_limits (공식 문서화된 값).
       tools/statusline_forward.py 가 이 서버의 /claude/statusline 으로 보내줍니다.
2순위: api.anthropic.com/api/oauth/usage (비공식). Claude Code를 쓰지 않는 동안에도
       값을 갱신하려고 주기적으로 조회합니다.

토큰은 읽기만 하고 절대 갱신(refresh)하지 않습니다. 갱신하면 refresh token이
교체되어 Claude Code 로그인이 풀릴 수 있기 때문입니다.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Optional

import aiohttp

from ..config import Config
from ..state import Hub, UsageWindow, sleep_or_wake
from ..util import first, http_session, to_epoch, to_percent

log = logging.getLogger("aidock.claude")

USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

LABELS = {
    "five_hour": "5시간",
    "seven_day": "주간",
    "seven_day_opus": "주간 (Opus)",
    "seven_day_sonnet": "주간 (Sonnet)",
}


def parse_statusline(payload: dict[str, Any]) -> list[UsageWindow]:
    """statusline JSON의 rate_limits → UsageWindow 목록."""
    rl = payload.get("rate_limits") or {}
    out: list[UsageWindow] = []
    for key, raw in rl.items():
        if not isinstance(raw, dict):
            continue
        pct = to_percent(first(raw, "used_percentage", "used_percent", "utilization"))
        if pct is None:
            continue
        out.append(UsageWindow(
            key=key,
            label=LABELS.get(key, key),
            used_percent=pct,
            resets_at=to_epoch(first(raw, "resets_at", "reset_at")),
        ))
    return out


def parse_oauth_usage(data: dict[str, Any]) -> list[UsageWindow]:
    out: list[UsageWindow] = []
    for key, raw in data.items():
        if not isinstance(raw, dict):
            continue
        pct = to_percent(first(raw, "utilization", "used_percentage"))
        if pct is None:
            continue
        out.append(UsageWindow(
            key=key,
            label=LABELS.get(key, key),
            used_percent=pct,
            resets_at=to_epoch(first(raw, "resets_at", "reset_at")),
        ))
    order = list(LABELS)
    out.sort(key=lambda w: order.index(w.key) if w.key in order else 99)
    return out


def _config_dir() -> Path:
    return Path(os.environ.get("CLAUDE_CONFIG_DIR", Path.home() / ".claude"))


def read_access_token() -> Optional[str]:
    """Claude Code의 OAuth access token을 읽습니다. 만료됐으면 None."""
    raw: Optional[str] = None
    cred_file = _config_dir() / ".credentials.json"
    if cred_file.exists():
        raw = cred_file.read_text(encoding="utf-8")
    elif sys.platform == "darwin":
        # macOS는 키체인에 저장합니다. 처음 한 번 접근 허용 창이 뜰 수 있습니다.
        try:
            raw = subprocess.run(
                ["security", "find-generic-password", "-s", "Claude Code-credentials", "-w"],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip() or None
        except (OSError, subprocess.SubprocessError):
            raw = None
    if not raw:
        return None
    try:
        oauth = json.loads(raw).get("claudeAiOauth") or {}
    except ValueError:
        return None
    token = oauth.get("accessToken")
    expires = to_epoch(oauth.get("expiresAt"))
    if not token or (expires and expires < time.time() + 30):
        return None
    return token


class ClaudeCollector:
    def __init__(self, cfg: Config, hub: Hub, refresh: asyncio.Event) -> None:
        self.cfg = cfg
        self.hub = hub
        self.refresh = refresh
        self._backoff_until = 0.0

    async def on_statusline(self, payload: dict[str, Any]) -> None:
        windows = parse_statusline(payload)
        if windows:
            # statusline에는 5시간/주간만 있으므로, oauth로 받아둔 다른 창(Opus 주간 등)은 유지
            merged = {w.key: w for w in self.hub.providers["claude"].windows}
            merged.update({w.key: w for w in windows})
            await self.hub.set_usage("claude", windows=list(merged.values()), source="statusline")

    async def poll_once(self, session: aiohttp.ClientSession) -> None:
        if time.time() < self._backoff_until:
            return
        token = await asyncio.to_thread(read_access_token)
        if not token:
            if sys.platform != "darwin" and not (_config_dir() / ".credentials.json").exists():
                # 데스크톱 앱만 쓰면 이 파일이 생기지 않습니다(앱이 로그인을 따로 관리).
                msg = "CLI 로그인 정보 없음 (터미널에서 claude 로그인 또는 상태줄 연결 필요)"
            else:
                msg = "Claude Code 로그인 토큰이 만료됨 (claude를 한 번 실행하면 다시 읽힘)"
            await self.hub.set_status("claude", "unavailable", msg)
            return
        headers = {
            "Authorization": f"Bearer {token}",
            "anthropic-beta": "oauth-2025-04-20",
            "User-Agent": "aidock-companion",
        }
        async with session.get(USAGE_URL, headers=headers, timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status == 429:
                self._backoff_until = time.time() + 15 * 60
                await self.hub.set_status("claude", "error", "oauth/usage 429 (15분 대기)")
                return
            if r.status != 200:
                await self.hub.set_status("claude", "error", f"oauth/usage HTTP {r.status}")
                return
            data = await r.json(content_type=None)
        windows = parse_oauth_usage(data)
        if windows:
            await self.hub.set_usage("claude", windows=windows, source="oauth/usage")
        else:
            await self.hub.set_status("claude", "error", "oauth/usage 응답 형식이 바뀐 것 같음")

    async def run(self) -> None:
        async with http_session() as session:
            while True:
                try:
                    await self.poll_once(session)
                except Exception as e:  # 네트워크 오류 등
                    log.warning("Claude 조회 실패: %s", e)
                    await self.hub.set_status("claude", "error", str(e)[:120])
                await sleep_or_wake(self.refresh, self.cfg.claude_poll_sec)
