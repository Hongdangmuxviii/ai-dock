"""Gemini CLI 사용량 (비공식).

Gemini CLI가 /stats 에서 보여주는 할당량은 Code Assist 내부 API에서 옵니다.
  1) POST v1internal:loadCodeAssist  → 계정에 연결된 프로젝트 ID
  2) POST v1internal:retrieveUserQuota → 모델별 남은 비율(remainingFraction)과 초기화 시각
토큰은 ~/.gemini/oauth_creds.json (Gemini CLI 로그인 정보)에서 읽습니다.
공식 API가 아니라서 언제든 바뀔 수 있습니다.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from pathlib import Path
from typing import Any, Optional

import aiohttp

from ..config import Config
from ..state import Hub, UsageWindow, sleep_or_wake
from ..util import first, http_session, to_epoch

log = logging.getLogger("aidock.gemini")

BASE = "https://cloudcode-pa.googleapis.com/v1internal"
TOKEN_URL = "https://oauth2.googleapis.com/token"
CREDS = Path.home() / ".gemini" / "oauth_creds.json"
METADATA = {"ideType": "IDE_UNSPECIFIED", "platform": "PLATFORM_UNSPECIFIED", "pluginType": "GEMINI"}


def parse_quota(data: dict[str, Any]) -> list[UsageWindow]:
    out: list[UsageWindow] = []
    for b in data.get("buckets") or []:
        frac = b.get("remainingFraction")
        if frac is None:
            continue
        model = b.get("modelId") or "model"
        if model.endswith("_vertex"):
            continue
        token_type = (b.get("tokenType") or "").lower()
        label = model.replace("gemini-", "")
        if token_type and token_type != "requests":
            label += f" ({token_type})"
        out.append(UsageWindow(
            key=f"{model}:{token_type}",
            label=label,
            used_percent=round(max(0.0, min(1.0, 1 - float(frac))) * 100, 1),
            resets_at=to_epoch(first(b, "resetTime", "reset_time")),
        ))
    # 많이 쓴 모델부터 보이게
    out.sort(key=lambda w: -w.used_percent)
    return out


class GeminiCollector:
    def __init__(self, cfg: Config, hub: Hub, refresh: asyncio.Event) -> None:
        self.cfg = cfg
        self.hub = hub
        self.refresh = refresh
        self._project: Optional[str] = None
        self._token: Optional[str] = None
        self._token_exp = 0.0

    async def _access_token(self, session: aiohttp.ClientSession) -> Optional[str]:
        if self._token and self._token_exp > time.time() + 60:
            return self._token
        try:
            creds = json.loads(CREDS.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        exp = to_epoch(creds.get("expiry_date")) or 0
        if creds.get("access_token") and exp > time.time() + 60:
            self._token, self._token_exp = creds["access_token"], exp
            return self._token
        # 만료됨. client id/secret이 설정돼 있을 때만 메모리에서 갱신(파일은 건드리지 않음).
        cid, secret = self.cfg.gemini_oauth_client_id, self.cfg.gemini_oauth_client_secret
        if not (cid and secret and creds.get("refresh_token")):
            return None
        async with session.post(TOKEN_URL, data={
            "client_id": cid, "client_secret": secret,
            "refresh_token": creds["refresh_token"], "grant_type": "refresh_token",
        }, timeout=aiohttp.ClientTimeout(total=15)) as r:
            if r.status != 200:
                return None
            tok = await r.json(content_type=None)
        self._token = tok.get("access_token")
        self._token_exp = time.time() + float(tok.get("expires_in", 3000))
        return self._token

    async def _post(self, session: aiohttp.ClientSession, method: str, body: dict, token: str) -> dict:
        async with session.post(
            f"{BASE}:{method}", json=body,
            headers={"Authorization": f"Bearer {token}", "User-Agent": "aidock-companion"},
            timeout=aiohttp.ClientTimeout(total=20),
        ) as r:
            if r.status != 200:
                raise RuntimeError(f"{method} HTTP {r.status}")
            return await r.json(content_type=None)

    async def poll_once(self, session: aiohttp.ClientSession) -> None:
        token = await self._access_token(session)
        if not token:
            await self.hub.set_status(
                "gemini", "unavailable",
                "Gemini CLI 토큰이 없거나 만료됨 (gemini를 한 번 실행하면 다시 읽힘)")
            return
        if not self._project:
            info = await self._post(session, "loadCodeAssist", {"metadata": METADATA}, token)
            proj = info.get("cloudaicompanionProject")
            if isinstance(proj, dict):
                proj = proj.get("id")
            self._project = proj
        body = {"project": self._project} if self._project else {}
        data = await self._post(session, "retrieveUserQuota", body, token)
        windows = parse_quota(data)
        if windows:
            await self.hub.set_usage("gemini", windows=windows, source="retrieveUserQuota")
        else:
            await self.hub.set_status("gemini", "error", "할당량 응답이 비어 있음")

    async def run(self) -> None:
        async with http_session() as session:
            while True:
                try:
                    await self.poll_once(session)
                except Exception as e:
                    log.warning("Gemini 조회 실패: %s", e)
                    self._project = None
                    await self.hub.set_status("gemini", "error", str(e)[:120])
                await sleep_or_wake(self.refresh, self.cfg.gemini_poll_sec)
