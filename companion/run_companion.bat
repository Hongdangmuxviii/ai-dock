@echo off
rem AI Dock companion launcher (Windows). Creates venv and installs packages on first run.
cd /d "%~dp0"
if not exist .venv (
    py -3 -m venv .venv || python -m venv .venv
    .venv\Scripts\python -m pip install -r requirements.txt
)
.venv\Scripts\python -m dock_companion
