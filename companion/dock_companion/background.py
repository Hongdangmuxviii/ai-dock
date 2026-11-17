"""기기 배경화면으로 쓸 이미지를 PC에서 보냅니다.

~/.aidock/background.jpg (또는 .jpeg/.png/.webp) 를 두면 기기로 전송하고, 파일을 바꾸거나
지우면 몇 초 안에 기기에도 반영됩니다. 기기 앱의 연결 설정에서 배경을 "PC 이미지"로
골라야 이 이미지가 보입니다(기본값).
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
from pathlib import Path
from typing import Optional

from .config import DATA_DIR
from .state import Hub

log = logging.getLogger("aidock.background")

NAMES = ("background.jpg", "background.jpeg", "background.png", "background.webp")
MIME = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
MAX_BYTES = 8 * 1024 * 1024


def _find() -> Optional[Path]:
    for name in NAMES:
        p = DATA_DIR / name
        if p.is_file():
            return p
    return None


class BackgroundWatcher:
    def __init__(self, hub: Hub, interval: float = 5.0) -> None:
        self.hub = hub
        self.interval = interval
        self._sig: Optional[tuple] = None

    async def _check(self) -> None:
        path = _find()
        sig = None
        if path:
            st = path.stat()
            sig = (str(path), st.st_mtime_ns, st.st_size)
        if sig == self._sig:
            return
        self._sig = sig
        if path is None:
            if self.hub.background is not None:
                log.info("배경 이미지 없음 → 기기 기본 배경")
                await self.hub.set_background(None)
            return
        if sig[2] > MAX_BYTES:
            log.warning("배경 이미지가 너무 큼(%.1fMB, 최대 8MB): %s", sig[2] / 1e6, path)
            return
        data = await asyncio.to_thread(path.read_bytes)
        image = {
            "mime": MIME.get(path.suffix.lower(), "image/jpeg"),
            "id": hashlib.sha1(data).hexdigest()[:16],
            "b64": base64.b64encode(data).decode(),
        }
        log.info("배경 이미지 전송: %s (%.1fMB)", path.name, len(data) / 1e6)
        await self.hub.set_background(image)

    async def run(self) -> None:
        while True:
            try:
                await self._check()
            except OSError as e:
                log.warning("배경 이미지 읽기 실패: %s", e)
            await asyncio.sleep(self.interval)
