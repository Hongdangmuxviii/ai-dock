from __future__ import annotations

import sys


class UnsupportedMedia:
    def __init__(self, hub, cfg) -> None:
        self.hub = hub

    async def run(self) -> None:
        await self.hub.set_media({
            "available": False,
            "reason": f"{sys.platform}에서는 미디어 제어를 아직 지원하지 않음",
        })

    async def command(self, data: dict) -> None:
        pass
