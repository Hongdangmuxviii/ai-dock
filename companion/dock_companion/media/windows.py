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
    # pywinrt (winrt-* 패키지). 예전 winsdk가 설치돼 있으면 그것도 씁니다.
    from winrt.windows.media.control import (
        GlobalSystemMediaTransportControlsSessionManager as SessionManager,
    )
    from winrt.windows.storage.streams import Buffer, DataReader, InputStreamOptions
except ImportError:  # pragma: no cover
    try:
        from winsdk.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager as SessionManager,
        )
        from winsdk.windows.storage.streams import Buffer, DataReader, InputStreamOptions
    except ImportError:
        SessionManager = None

# GlobalSystemMediaTransportControlsSessionPlaybackStatus
STATUS = {0: "closed", 1: "opened", 2: "changing", 3: "stopped", 4: "playing", 5: "paused"}
# Firefox는 앱 ID가 "308046B0AF4A39CB" 같은 설치 경로 해시로 나와서 따로 넣어둡니다.
BROWSERS = ("chrome", "msedge", "firefox", "brave", "whale", "opera", "308046b0af4a39cb")

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
        # ---- 재생 위치 ----
        # Firefox 등 브라우저는 타임라인을 대부분 0으로 보내다가 가끔(재생/탐색/주기적으로)
        # 0.3초 정도만 실제 값을 보냅니다. 1초 폴링으로는 거의 못 잡으므로 변경 이벤트를 구독해
        # 실제 값이 올 때마다 기억하고(_raw), 그 사이에는 마지막 실제 값에서 흐른 시간으로 계산합니다.
        self._raw: dict[str, tuple[float, float, float, float]] = {}  # aumid -> (pos, at, dur, 받은 시각)
        self._subscribed: dict[str, Any] = {}  # aumid -> (session, token)
        self._track_since = 0.0
        self._tl: Optional[dict[str, float]] = None  # 지금 곡의 {"pos", "at", "dur", "src"}
        self._last_status = ""

    # ---- 타임라인 ----
    def _record(self, aumid: str, tl) -> None:
        """실제 값(길이 > 0)인 타임라인만 기억합니다. 이벤트 스레드에서도 불립니다."""
        try:
            dur = _seconds(tl.end_time) - _seconds(tl.start_time)
            if dur <= 0:
                return
            try:
                at = tl.last_updated_time.timestamp()
            except Exception:
                at = time.time()
            self._raw[aumid] = (_seconds(tl.position), at, dur, time.time())
        except Exception:
            pass

    def _subscribe(self, sessions) -> None:
        for s in sessions:
            aumid = s.source_app_user_model_id or ""
            if aumid in self._subscribed:
                continue
            try:
                token = s.add_timeline_properties_changed(
                    lambda sender, _args, a=aumid: self._record(a, sender.get_timeline_properties()))
                self._subscribed[aumid] = (s, token)
            except Exception as e:
                log.debug("타임라인 이벤트 구독 실패 %s: %s", aumid, e)

    def _on_sessions_changed(self, *_args) -> None:
        # 세션이 새로 생기거나 사라지면 다음 폴링 때 다시 구독
        self._subscribed = {}

    def _timeline(self, aumid: str, key: str, status: str, tl) -> dict[str, float]:
        self._record(aumid, tl)
        now = time.time()
        if key != self._thumb_key:
            self._track_since = now
            self._tl = None
        raw = self._raw.get(aumid)
        # 곡이 바뀐 뒤에 받은 실제 값만 씁니다(이전 곡 값이 섞이지 않게)
        if raw and raw[3] >= self._track_since - 0.5 and (self._tl is None or raw[1] != self._tl["src"]):
            self._tl = {"pos": raw[0], "at": raw[1], "dur": raw[2], "src": raw[1]}
        if self._tl is not None:
            if self._last_status == "playing" and status != "playing":
                # 일시정지: 흐른 시간만큼 앞으로 옮겨서 멈춘 위치로 고정
                self._tl["pos"] = min(self._tl["pos"] + (now - self._tl["at"]), self._tl["dur"])
                self._tl["at"] = now
            elif self._last_status != "playing" and status == "playing":
                self._tl["at"] = now
        self._last_status = status
        if self._tl is None:
            return {"position": 0.0, "position_at": round(now, 1), "duration": 0.0}
        return {
            "position": round(self._tl["pos"], 1),
            "position_at": round(self._tl["at"], 1),
            "duration": round(self._tl["dur"], 1),
        }

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
        title = props.title or ""
        artist = props.artist or ""
        aumid = s.source_app_user_model_id or ""

        key = f"{aumid}|{title}|{artist}"
        timeline = self._timeline(aumid, key, status, tl)

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
            **timeline,
        }

    async def run(self) -> None:
        if SessionManager is None:
            await self.hub.set_media({"available": False, "reason": "winrt 미설치 (pip install -r requirements.txt)"})
            return
        self.manager = await SessionManager.request_async()
        try:
            self.manager.add_sessions_changed(self._on_sessions_changed)
        except Exception as e:
            log.debug("세션 변경 이벤트 구독 실패: %s", e)
        while True:
            try:
                self._subscribe(self.manager.get_sessions())
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
            # 브라우저가 새 위치를 늦게 알려줘도 재생바가 바로 옮겨지게
            if self._tl is not None:
                self._tl["pos"] = min(seconds, self._tl["dur"])
                self._tl["at"] = time.time()
        # 명령 직후 화면이 바로 반영되도록 즉시 한 번 더 읽습니다.
        await asyncio.sleep(0.15)
        await self.hub.set_media(await self._snapshot())
