"""플랫폼별 미디어 제어. 지금은 윈도우(SMTC)만 구현되어 있습니다."""
from __future__ import annotations

import sys


def create_media_controller(hub, cfg):
    if sys.platform == "win32":
        from .windows import WindowsMedia
        return WindowsMedia(hub, cfg)
    from .unsupported import UnsupportedMedia
    return UnsupportedMedia(hub, cfg)
