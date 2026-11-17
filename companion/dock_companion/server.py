"""HTTP + WebSocket 서버.

- GET  /ws                : 기기 앱이 붙는 웹소켓
- POST /claude/statusline : Claude Code 상태줄 스크립트가 rate_limits를 보내는 곳 (로컬 전용)
- GET  /state             : 디버깅용 현재 상태 JSON
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Awaitable, Callable, Optional

from aiohttp import WSMsgType, web

from .config import Config
from .state import Hub

log = logging.getLogger("aidock.server")

MediaHandler = Callable[[dict[str, Any]], Awaitable[None]]
StatuslineHandler = Callable[[dict[str, Any]], Awaitable[None]]


class DockServer:
    def __init__(self, cfg: Config, hub: Hub, on_refresh: Callable[[], None]) -> None:
        self.cfg = cfg
        self.hub = hub
        self.on_refresh = on_refresh
        self.on_media_command: Optional[MediaHandler] = None
        self.on_statusline: Optional[StatuslineHandler] = None
        self.app = web.Application()
        self.app.add_routes([
            web.get("/ws", self._ws),
            web.post("/claude/statusline", self._statusline),
            web.get("/state", self._state),
        ])

    def _authorized(self, request: web.Request) -> bool:
        if not self.cfg.token:
            return True
        return request.query.get("token") == self.cfg.token

    async def _ws(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            raise web.HTTPUnauthorized(text="bad token")
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        log.info("기기 접속: %s", request.remote)

        send_lock = asyncio.Lock()

        async def send(msg: dict[str, Any]) -> None:
            async with send_lock:
                await ws.send_str(json.dumps(msg, ensure_ascii=False))

        await send({"type": "hello", "version": 1})
        await send(self.hub.usage_message())
        await send({"type": "media", **self.hub.media})
        await send({"type": "thumbnail", "image": self.hub.thumbnail})
        self.hub.subscribe(send)
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    data = json.loads(msg.data)
                except ValueError:
                    continue
                kind = data.get("type")
                if kind == "refresh":
                    self.on_refresh()
                elif kind == "media_cmd" and self.on_media_command:
                    try:
                        await self.on_media_command(data)
                    except Exception:
                        log.exception("미디어 명령 실패: %s", data)
                elif kind == "ping":
                    await send({"type": "pong"})
        finally:
            self.hub.unsubscribe(send)
            log.info("기기 연결 끊김: %s", request.remote)
        return ws

    async def _statusline(self, request: web.Request) -> web.Response:
        if request.remote not in ("127.0.0.1", "::1"):
            raise web.HTTPForbidden(text="local only")
        try:
            data = await request.json()
        except ValueError:
            raise web.HTTPBadRequest(text="json")
        if self.on_statusline:
            await self.on_statusline(data)
        return web.json_response({"ok": True})

    async def _state(self, request: web.Request) -> web.Response:
        if not self._authorized(request):
            raise web.HTTPUnauthorized(text="bad token")
        return web.json_response({
            "usage": self.hub.usage_message(),
            "media": self.hub.media,
            "has_thumbnail": self.hub.thumbnail is not None,
        })

    async def start(self) -> web.AppRunner:
        runner = web.AppRunner(self.app, access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, self.cfg.host, self.cfg.port)
        await site.start()
        log.info("서버 시작: %s:%d", self.cfg.host, self.cfg.port)
        return runner
