from __future__ import annotations

import ssl
from datetime import datetime
from typing import Any, Optional


def to_epoch(value: Any) -> Optional[float]:
    """epoch 초/밀리초 숫자나 ISO-8601 문자열을 epoch 초로 바꿉니다."""
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)):
        v = float(value)
        return v / 1000.0 if v > 1e12 else v
    if isinstance(value, str):
        s = value.strip()
        try:
            return to_epoch(float(s))
        except ValueError:
            pass
        try:
            return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None
    return None


def to_percent(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    return max(0.0, min(100.0, v))


def first(d: dict[str, Any], *keys: str) -> Any:
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return None


def http_session():
    """certifi 인증서 번들을 쓰는 aiohttp 세션 (python.org 설치본의 인증서 문제 회피)."""
    import aiohttp
    try:
        import certifi
        ctx = ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        ctx = ssl.create_default_context()
    return aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=ctx))
