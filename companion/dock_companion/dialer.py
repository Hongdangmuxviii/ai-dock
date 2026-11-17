"""PC가 기기로 먼저 접속하는 방식(역방향 연결).

PC가 서브 공유기(이중 NAT) 뒤에 있으면 기기는 PC에 접속할 수 없지만, PC는 기기에 접속할 수
있습니다. 그래서 기기 앱이 device_port(기본 8766)에서 웹소켓 접속을 기다리고, 컴패니언이
그쪽으로 붙습니다. 연결된 뒤 주고받는 메시지는 기기가 /ws 로 접속했을 때와 똑같습니다.

기기 IP가 바뀌어도 device_scan 대역을 훑어 다시 찾고, 마지막으로 찾은 주소는
~/.aidock/device.json 에 기억해둡니다.
"""
from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import socket
import time
from typing import Optional

import aiohttp

from .config import DATA_DIR, Config
from .server import DockServer

log = logging.getLogger("aidock.dialer")

REMEMBER = DATA_DIR / "device.json"


def _split(addr: str, default_port: int) -> Optional[tuple[str, int]]:
    addr = addr.strip()
    if not addr:
        return None
    host, _, port = addr.rpartition(":") if ":" in addr else (addr, "", "")
    try:
        return host, int(port) if port else default_port
    except ValueError:
        return None


def _own_ips() -> set[str]:
    try:
        return {i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
    except OSError:
        return set()


class DeviceDialer:
    def __init__(self, cfg: Config, server: DockServer) -> None:
        self.cfg = cfg
        self.server = server
        self._last_scan = 0.0

    # ---- 기억해둔 주소 ----
    def _remembered(self) -> Optional[tuple[str, int]]:
        try:
            d = json.loads(REMEMBER.read_text(encoding="utf-8"))
            return d["host"], int(d["port"])
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _remember(self, host: str, port: int) -> None:
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            REMEMBER.write_text(json.dumps({"host": host, "port": port}), encoding="utf-8")
        except OSError:
            pass

    # ---- 대역 스캔 ----
    async def _scan(self) -> list[tuple[str, int]]:
        port = self.cfg.device_port
        try:
            net = ipaddress.ip_network(self.cfg.device_scan.strip(), strict=False)
        except ValueError:
            log.warning("device_scan 형식이 잘못됨: %r", self.cfg.device_scan)
            return []
        if net.num_addresses > 1024:
            log.warning("device_scan 대역이 너무 넓음(최대 /22): %s", net)
            return []
        own = _own_ips()
        sem = asyncio.Semaphore(128)
        found: list[tuple[str, int]] = []

        async def probe(ip: str) -> None:
            async with sem:
                try:
                    _, w = await asyncio.wait_for(asyncio.open_connection(ip, port), 0.8)
                    w.close()
                    found.append((ip, port))
                except (OSError, asyncio.TimeoutError):
                    pass

        await asyncio.gather(*(probe(str(ip)) for ip in net.hosts() if str(ip) not in own))
        log.info("기기 찾기: %s 에서 %d개 응답 %s", net, len(found), [h for h, _ in found])
        return found

    # ---- 연결 ----
    async def _session(self, http: aiohttp.ClientSession, host: str, port: int) -> bool:
        """연결에 성공했으면 끊길 때까지 처리하고 True, 연결 자체가 안 되면 False."""
        url = f"ws://{host}:{port}/ws"
        params = {"token": self.cfg.token} if self.cfg.token else None
        try:
            ws = await http.ws_connect(url, params=params, heartbeat=20, autoping=True)
        except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as e:
            log.debug("기기 접속 실패 %s: %s", url, e)
            return False
        log.info("기기에 접속함: %s:%d", host, port)
        self._remember(host, port)
        try:
            await self.server.run_session(ws)
        except Exception as e:
            log.info("기기 세션 오류: %s", e)
        finally:
            await ws.close()
            log.info("기기 연결 끊김: %s:%d", host, port)
        return True

    async def run(self) -> None:
        timeout = aiohttp.ClientTimeout(total=None, connect=4, sock_connect=4)
        backoff = 3.0
        async with aiohttp.ClientSession(timeout=timeout) as http:
            while True:
                candidates: list[tuple[str, int]] = []
                for c in (_split(self.cfg.device_address, self.cfg.device_port), self._remembered()):
                    if c and c not in candidates:
                        candidates.append(c)

                connected = False
                for host, port in candidates:
                    if await self._session(http, host, port):
                        connected = True
                        break

                if not connected and self.cfg.device_scan and time.time() - self._last_scan > 30:
                    self._last_scan = time.time()
                    for host, port in await self._scan():
                        if (host, port) in candidates:
                            continue
                        if await self._session(http, host, port):
                            connected = True
                            break

                if connected:
                    backoff = 3.0
                    await asyncio.sleep(2)
                else:
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 30.0)
