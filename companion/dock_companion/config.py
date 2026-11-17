"""설정 로딩. config.json(선택)이 없으면 기본값을 씁니다."""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field, fields
from pathlib import Path

HOME = Path.home()
DATA_DIR = Path(os.environ.get("AIDOCK_DATA_DIR", HOME / ".aidock"))


@dataclass
class Config:
    host: str = "0.0.0.0"
    port: int = 8765
    # 비워두면 인증 없음. 값을 넣으면 기기 앱에도 같은 값을 입력해야 접속됩니다.
    token: str = ""
    # mDNS로 기기가 PC를 자동으로 찾게 할지
    advertise_mdns: bool = True

    # 사용량 조회 주기(초). 비공식 엔드포인트라 너무 짧게 잡지 마세요.
    claude_poll_sec: int = 300
    codex_poll_sec: int = 300
    gemini_poll_sec: int = 600

    claude_enabled: bool = True
    codex_enabled: bool = True
    gemini_enabled: bool = True

    # 비워두면 PATH에서 codex를 찾습니다.
    codex_path: str = ""

    # Gemini CLI의 토큰이 만료됐을 때 갱신하려면 Gemini CLI가 쓰는 OAuth
    # client id/secret이 필요합니다(gemini-cli 소스에 공개되어 있음).
    # 비워두면 갱신하지 않고, gemini를 한 번 실행하면 다시 읽힙니다.
    gemini_oauth_client_id: str = ""
    gemini_oauth_client_secret: str = ""

    media_enabled: bool = True
    media_poll_sec: float = 1.0

    extra: dict = field(default_factory=dict)


def load_config() -> Config:
    path = Path(os.environ.get("AIDOCK_CONFIG", DATA_DIR / "config.json"))
    cfg = Config()
    if path.exists():
        raw = json.loads(path.read_text(encoding="utf-8"))
        known = {f.name for f in fields(Config)}
        for k, v in raw.items():
            if k in known:
                setattr(cfg, k, v)
            else:
                cfg.extra[k] = v
    return cfg
