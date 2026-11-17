"""실행: python -m dock_companion"""
from __future__ import annotations

import asyncio
import logging
import sys

from .collectors.claude import ClaudeCollector
from .collectors.codex import CodexCollector
from .collectors.gemini import GeminiCollector
from .config import load_config
from .mdns import MdnsAdvertiser
from .media import create_media_controller
from .server import DockServer
from .state import Hub


async def main() -> None:
    cfg = load_config()
    hub = Hub(enabled=[pid for pid, on in (
        ("claude", cfg.claude_enabled),
        ("codex", cfg.codex_enabled),
        ("gemini", cfg.gemini_enabled),
    ) if on])
    tasks: list[asyncio.Task] = []
    refresh_events: list[asyncio.Event] = []

    def new_event() -> asyncio.Event:
        ev = asyncio.Event()
        refresh_events.append(ev)
        return ev

    def trigger_refresh() -> None:
        for ev in refresh_events:
            ev.set()

    server = DockServer(cfg, hub, trigger_refresh)

    if cfg.claude_enabled:
        claude = ClaudeCollector(cfg, hub, new_event())
        server.on_statusline = claude.on_statusline
        tasks.append(asyncio.create_task(claude.run(), name="claude"))
    if cfg.codex_enabled:
        tasks.append(asyncio.create_task(CodexCollector(cfg, hub, new_event()).run(), name="codex"))
    if cfg.gemini_enabled:
        tasks.append(asyncio.create_task(GeminiCollector(cfg, hub, new_event()).run(), name="gemini"))
    if cfg.media_enabled:
        media = create_media_controller(hub, cfg)
        server.on_media_command = media.command
        tasks.append(asyncio.create_task(media.run(), name="media"))

    from .background import BackgroundWatcher
    tasks.append(asyncio.create_task(BackgroundWatcher(hub).run(), name="background"))

    runner = await server.start()
    if cfg.device_address or cfg.device_scan:
        from .dialer import DeviceDialer
        tasks.append(asyncio.create_task(DeviceDialer(cfg, server).run(), name="dialer"))
    mdns = MdnsAdvertiser(cfg.port, bool(cfg.token)) if cfg.advertise_mdns else None
    if mdns:
        await mdns.start()
    try:
        await asyncio.Event().wait()
    finally:
        for t in tasks:
            t.cancel()
        if mdns:
            await mdns.stop()
        await runner.cleanup()


def _setup_logging() -> None:
    fmt = "%(asctime)s %(name)s %(levelname)s %(message)s"
    if sys.stderr is None:
        # pythonw(콘솔 창 없이 실행)일 때는 ~/.aidock/companion.log 에 남깁니다.
        from logging.handlers import RotatingFileHandler
        from .config import DATA_DIR
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(DATA_DIR / "companion.log", maxBytes=1_000_000,
                                      backupCount=2, encoding="utf-8")
        handler.setFormatter(logging.Formatter(fmt, "%Y-%m-%d %H:%M:%S"))
        logging.basicConfig(level=logging.INFO, handlers=[handler])
    else:
        logging.basicConfig(level=logging.INFO, format=fmt, datefmt="%H:%M:%S")


if __name__ == "__main__":
    _setup_logging()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
