@echo off
chcp 65001 >nul
rem AI Dock companion launcher (Windows). Creates venv and installs packages on first run.
cd /d "%~dp0"
set PYTHONUTF8=1

if not exist .venv\Scripts\python.exe (
    echo [AI Dock] 가상환경을 만드는 중...
    py -3 -m venv .venv 2>nul || python -m venv .venv
    if not exist .venv\Scripts\python.exe (
        echo [AI Dock] Python을 찾지 못했습니다. Python 3.11 이상을 설치하고 "Add python.exe to PATH"를 체크하세요.
        pause
        exit /b 1
    )
)

rem requirements.txt가 바뀌었거나 이전 설치가 실패했으면 다시 설치합니다.
fc /b requirements.txt .venv\installed-requirements.txt >nul 2>&1
if errorlevel 1 (
    echo [AI Dock] 패키지를 설치하는 중... ^(1~2분^)
    .venv\Scripts\python -m pip install --disable-pip-version-check -q -r requirements.txt
    if errorlevel 1 (
        echo [AI Dock] 패키지 설치에 실패했습니다. 위 오류를 확인하세요.
        pause
        exit /b 1
    )
    copy /y requirements.txt .venv\installed-requirements.txt >nul
)

.venv\Scripts\python -m dock_companion
if errorlevel 1 pause
