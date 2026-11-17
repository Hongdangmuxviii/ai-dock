"""같은 네트워크의 기기가 PC를 자동으로 찾도록 _aidock._tcp 서비스를 광고합니다."""
from __future__ import annotations

import logging
import socket

log = logging.getLogger("aidock.mdns")

SERVICE_TYPE = "_aidock._tcp.local."


def _lan_ip() -> str:
    # 실제로 패킷을 보내지 않고, 외부로 나갈 때 쓰일 인터페이스 IP만 알아냅니다.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class MdnsAdvertiser:
    def __init__(self, port: int, needs_token: bool) -> None:
        self.port = port
        self.needs_token = needs_token
        self._zc = None
        self._info = None

    async def start(self) -> None:
        try:
            from zeroconf import ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf
        except ImportError:
            log.warning("zeroconf가 없어 자동 탐색을 끕니다. 기기에서 IP를 직접 입력하세요.")
            return
        ip = _lan_ip()
        host = socket.gethostname().split(".")[0] or "pc"
        self._info = ServiceInfo(
            SERVICE_TYPE,
            f"AI Dock ({host}).{SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=self.port,
            properties={"v": "1", "auth": "1" if self.needs_token else "0"},
            server=f"{host}.local.",
        )
        self._zc = AsyncZeroconf()
        await self._zc.async_register_service(self._info)
        log.info("mDNS 광고: %s:%d", ip, self.port)

    async def stop(self) -> None:
        if self._zc and self._info:
            await self._zc.async_unregister_service(self._info)
            await self._zc.async_close()
