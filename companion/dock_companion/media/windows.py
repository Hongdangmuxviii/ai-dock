"""윈도우 미디어 제어 (System Media Transport Controls).

볼륨 키를 누르면 뜨는 윈도우 미디어 팝업과 같은 API입니다. 크롬/엣지에서 재생 중인
유튜브의 제목, 채널, 썸네일, 재생 위치를 읽고 재생/일시정지, 이전/다음, 탐색을 합니다.
볼륨은 SMTC에 없어서 윈도우 시스템 볼륨 키를 대신 누릅니다.
"""
from __future__ import annotations

import asyncio
import base64
import ctypes
import logging
import time
from typing import Any, Optional

from ..config import Config
from ..state import Hub

log = logging.getLogger("aidock.media")

try:
    from winsdk.windows.media.control import (
        GlobalSystemMediaTransportControlsSessionManager as SessionManager,
    )
    from winsdk.windows.storage.streams import Buffer, DataReader, InputStreamOptions
except ImportError:  # pragma: no cover
    SessionManager = None

# GlobalSystemMediaTransportControlsSessionPlaybackStatus
STATUS = {0: "closed", 1: "opened", 2: "changing", 3: "stopped", 4: "playing", 5: "paused"}
BROWSERS = ("chrome", "msedge", "firefox", "brave", "whale", "opera")

VK = {"vol_up": 0xAF, "vol_down": 0xAE, "mute": 0xAD}
KEYEVENTF_KEYUP = 0x0002


def _press(vk: int) -> None:
    user32 = ctypes.windll.user32
    user32.keybd_event(vk, 0, 0, 0)
    user32.keybd_event(vk, 0, KEYEVENTF_KEYUP, 0)


def _seconds(td) -> float:
    try:
        return td.total_seconds()
    except AttributeError:
        return float(td or 0) / 10_000_000  # 100ns 단위 정수로 올 때


class WindowsMedia:
    def __init__(self, hub: Hub, cfg: Config) -> None:
        self.hub = hub
        self.cfg = cfg
        self.manager = None
        self._thumb_key: Optional[str] = None

    def _pick_session(self):
        """브라우저에서 재생 중인 세션 > 브라우저 세션 > 윈도우가 고른 현재 세션."""
        sessions = list(self.manager.get_sessions())
        browser = [s for s in sessions
                   if any(b in (s.source_app_user_model_id or "").lower() for b in BROWSERS)]
        for s in browser:
            if STATUS.get(s.get_playback_info().playback_status) == "playing":
                return s
        if browser:
            return browser[0]
        return self.manager.get_current_session()

    async def _read_thumbnail(self, props) -> Optional[dict[str, str]]:
        ref = props.thumbnail
        if ref is None:
            return None
        stream = await ref.open_read_async()
        size = int(stream.size)
        if size <= 0 or size > 2_000_000:
            return None
        buf = Buffer(size)
        await stream.read_async(buf, size, InputStreamOptions.READ_AHEAD)
        reader = DataReader.from_buffer(buf)
        data = bytearray(buf.length)
        reader.read_bytes(data)
        return {"mime": stream.content_type or "image/png",
                "b64": base64.b64encode(bytes(data)).decode()}

    async def _snapshot(self) -> dict[str, Any]:
        s = self._pick_session()
        if s is None:
            return {"available": True, "active": False}
        props = await s.try_get_media_properties_async()
        tl = s.get_timeline_properties()
        status = STATUS.get(s.get_playback_info().playback_status, "unknown")
        try:
            updated = tl.last_updated_time.timestamp()
        except Exception:
            updated = time.time()
        title = props.title or ""
        artist = props.artist or ""

        key = f"{s.source_app_user_model_id}|{title}|{artist}"
        if key != self._thumb_key:
            self._thumb_key = key
            try:
                await self.hub.set_thumbnail(await self._read_thumbnail(props))
            except Exception as e:
                log.debug("썸네일 실패: %s", e)
                await self.hub.set_thumbnail(None)

        return {
            "available": True,
            "active": True,
            "app": s.source_app_user_model_id,
            "title": title,
            "artist": artist,
            "status": status,
            # 기기에서 재생 중일 때 position + (지금 - position_at) 으로 진행바를 움직입니다.
            "position": round(_seconds(tl.position), 1),
            "position_at": round(updated, 1),
            "duration": round(_seconds(tl.end_time) - _seconds(tl.start_time), 1),
        }

    async def run(self) -> None:
        if SessionManager is None:
            await self.hub.set_media({"available": False, "reason": "winsdk 미설치 (pip install winsdk)"})
            return
        self.manager = await SessionManager.request_async()
        while True:
            try:
                await self.hub.set_media(await self._snapshot())
            except Exception as e:
                log.debug("미디어 상태 읽기 실패: %s", e)
            await asyncio.sleep(self.cfg.media_poll_sec)

    async def command(self, data: dict[str, Any]) -> None:
        cmd = data.get("cmd")
        if cmd in VK:
            for _ in range(int(data.get("repeat", 1))):
                _press(VK[cmd])
            return
        if self.manager is None:
            return
        s = self._pick_session()
        if s is None:
            return
        if cmd == "toggle":
            await s.try_toggle_play_pause_async()
        elif cmd == "play":
            await s.try_play_async()
        elif cmd == "pause":
            await s.try_pause_async()
        elif cmd == "next":
            await s.try_skip_next_async()
        elif cmd == "prev":
            await s.try_skip_previous_async()
        elif cmd == "seek":
            seconds = max(0.0, float(data.get("value", 0)))
            await s.try_change_playback_position_async(int(seconds * 10_000_000))
        # 명령 직후 화면이 바로 반영되도록 즉시 한 번 더 읽습니다.
        await asyncio.sleep(0.15)
        await self.hub.set_media(await self._snapshot())
