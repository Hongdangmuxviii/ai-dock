"""Claude Code 상태줄(statusLine) 스크립트.

Claude Code가 stdin으로 넘겨주는 JSON에서 rate_limits를 꺼내 컴패니언에 보내고,
상태줄에는 "5h 23% · 7d 41%" 같은 짧은 문구를 출력합니다.

~/.claude/settings.json 예시 (윈도우):
  "statusLine": {
    "type": "command",
    "command": "python C:\\\\path\\\\to\\\\companion\\\\tools\\\\statusline_forward.py"
  }

이미 쓰던 상태줄 스크립트가 있다면 환경변수 AIDOCK_STATUSLINE_CHAIN 에 그 명령을
넣어두세요. 같은 JSON을 그 명령에 넘기고, 그 출력을 대신 표시합니다.
표준 라이브러리만 씁니다(컴패니언 가상환경이 없어도 동작).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.request

PORT = int(os.environ.get("AIDOCK_PORT", "8765"))


def forward(payload: dict) -> None:
    rl = payload.get("rate_limits")
    if not rl:
        return
    body = json.dumps({"rate_limits": rl}).encode()
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/claude/statusline", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=0.5).read()
    except Exception:
        pass  # 컴패니언이 꺼져 있어도 상태줄은 정상 표시


def summary(payload: dict) -> str:
    rl = payload.get("rate_limits") or {}
    parts = []
    for key, short in (("five_hour", "5h"), ("seven_day", "7d")):
        w = rl.get(key) or {}
        pct = w.get("used_percentage")
        if pct is not None:
            parts.append(f"{short} {round(float(pct))}%")
    model = (payload.get("model") or {}).get("display_name")
    if model:
        parts.insert(0, model)
    return " · ".join(parts)


def main() -> None:
    # 윈도우 기본 콘솔 인코딩(cp949)이 아니라 UTF-8로 주고받아야 Claude Code가 글자를 제대로 읽습니다.
    for stream in (sys.stdin, sys.stdout):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass
    raw = sys.stdin.read()
    try:
        payload = json.loads(raw)
    except ValueError:
        payload = {}
    forward(payload)
    chain = os.environ.get("AIDOCK_STATUSLINE_CHAIN")
    if chain:
        out = subprocess.run(chain, input=raw, capture_output=True, text=True, shell=True)
        sys.stdout.write(out.stdout)
        return
    print(summary(payload))


if __name__ == "__main__":
    main()
