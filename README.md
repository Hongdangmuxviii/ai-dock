# AI Dock

토스프론트2(리니지OS)를 책상 위 대시보드로 씁니다.

- 시계
- 코딩 도구 사용량: Claude Code / Codex / Gemini CLI의 사용률과 초기화 시각
- PC 유튜브 리모컨: 재생/일시정지, 이전/다음, 탐색, 볼륨

```
[토스프론트2 앱] ⇅ 집 공유기 (와이파이 ↔ 랜선, WebSocket) ⇅ [윈도우 컴패니언]
```

| 폴더 | 내용 |
|---|---|
| `companion/` | 윈도우 PC에서 상주하는 Python 서버. 사용량 수집과 미디어 제어를 맡음 |
| `android/` | 토스프론트2에 설치하는 앱 (Kotlin + Jetpack Compose) |

---

## 1. 윈도우 컴패니언 설치

1. [Python 3.11 이상](https://www.python.org/downloads/windows/)을 설치합니다. 설치할 때 "Add python.exe to PATH"에 체크하세요.
2. `companion` 폴더를 PC에 복사한 뒤 `run_companion.bat`을 더블클릭합니다.
   - 처음 실행하면 가상환경을 만들고 패키지를 설치합니다(1~2분).
   - **윈도우 방화벽 창이 뜨면 "개인 네트워크"에 허용**하세요. 허용하지 않으면 기기가 PC에 접속할 수 없습니다.
3. 로그에 `서버 시작: 0.0.0.0:8765`가 나오면 준비가 끝난 것입니다. 브라우저에서 `http://localhost:8765/state`를 열면 현재 상태를 확인할 수 있습니다.

**PC를 켤 때 자동으로 실행하려면:** `Win + R`을 누르고 `shell:startup`을 입력해 시작프로그램 폴더를 엽니다. 거기에 `run_companion.bat`의 바로가기를 넣으세요.

### 설정 (선택)
`%USERPROFILE%\.aidock\config.json`을 만들면 기본값을 바꿀 수 있습니다. 예시는 `config.example.json`에 있습니다.
- `token`: 값을 넣으면 같은 토큰을 입력한 기기만 접속할 수 있습니다.
- `*_poll_sec`: 사용량 조회 간격(초)입니다. 비공식 엔드포인트라 5분보다 짧게 잡지 않는 걸 권장합니다.

## 2. 사용량 수집 준비

| 도구 | 필요한 것 | 가져오는 곳 |
|---|---|---|
| Claude Code | 윈도우에서 `claude` 로그인 + **상태줄 설정** (아래) | ① 상태줄 `rate_limits` (공식) ② `oauth/usage` (비공식, 5분마다) |
| Codex | `npm i -g @openai/codex` 설치 후 `codex login` | ① `codex app-server`의 `account/rateLimits/read` (공식) ② `wham/usage` (비공식) |
| Gemini CLI | `gemini` 실행 후 구글 로그인 | `retrieveUserQuota` (비공식) |

### Claude Code 상태줄 연결
`%USERPROFILE%\.claude\settings.json`에 다음을 추가합니다. 경로는 실제 위치로 바꾸세요.

```json
{
  "statusLine": {
    "type": "command",
    "command": "python C:\\AIDock\\companion\\tools\\statusline_forward.py"
  }
}
```

- 이제 Claude Code를 쓰는 동안 응답이 올 때마다 사용량이 실시간으로 기기에 반영됩니다.
- 상태줄에는 `Opus · 5h 23% · 7d 41%` 같은 문구가 표시됩니다.
- 이미 쓰던 상태줄 스크립트가 있다면, 그 명령을 환경변수 `AIDOCK_STATUSLINE_CHAIN`에 넣으세요. 원래 출력을 그대로 유지합니다.

> 컴패니언은 각 도구의 로그인 토큰을 **읽기만 하고 갱신하지 않습니다.** 갱신하면 CLI의 로그인이 풀릴 수 있기 때문입니다. 토큰이 만료되면 해당 CLI를 한 번 실행해주면 다시 읽힙니다.

## 3. 안드로이드 앱 빌드와 설치

1. [Android Studio](https://developer.android.com/studio)로 `android` 폴더를 엽니다. Gradle 동기화는 자동으로 진행됩니다.
2. 토스프론트2에서 개발자 옵션을 켜고 USB 디버깅(또는 무선 디버깅)을 활성화합니다.
3. Android Studio에서 ▶ Run을 누르면 설치됩니다. 또는 `Build > Build APK(s)`로 APK를 만들어 직접 설치해도 됩니다.

**Android Studio 없이 명령줄로 빌드할 때 (이 맥에 설치된 도구 사용):**
```bash
cd android && JAVA_HOME=~/.local/aidock-tools/jdk/Contents/Home ./gradlew assembleDebug
```
APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생깁니다. 기기에 설치하려면:
```bash
~/Library/Android/sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### 사용법
- 같은 공유기에 연결되어 있으면 **PC를 자동으로 찾습니다.** 우측 상단에 "PC 연결됨"이 표시됩니다.
- **시계를 길게 누르면** 연결 설정이 열립니다. 자동 탐색이 안 될 때 PC 주소(예: `192.168.0.10:8765`)와 토큰을 직접 입력하세요.
- **사용량 카드를 길게 누르면** 즉시 새로고침합니다.
- 화면 방향에 따라 배치가 바뀝니다. 세로일 때는 시계 → 사용량 → 리모컨 순서이고, 가로일 때는 왼쪽에 시계와 사용량, 오른쪽에 리모컨이 옵니다.
- PC가 꺼져 있어도 마지막 값과 초기화 카운트다운은 계속 표시됩니다. 초기화 시각이 지나면 0%로 표시합니다.

## 프로토콜 (WebSocket `/ws`)

서버 → 기기
```jsonc
{"type":"usage","server_time":1794880000,"providers":[{"id":"claude","name":"Claude Code","status":"ok","source":"statusline","updated_at":...,"windows":[{"key":"five_hour","label":"5시간","used_percent":23.4,"resets_at":1794883600}],"message":""}]}
{"type":"media","available":true,"active":true,"title":"...","artist":"...","status":"playing","position":61.2,"position_at":1794880000,"duration":245.0}
{"type":"thumbnail","image":{"mime":"image/png","b64":"..."}}
```
기기 → 서버
```jsonc
{"type":"media_cmd","cmd":"toggle|play|pause|next|prev|seek|vol_up|vol_down|mute","value":120}
{"type":"refresh"}
```
