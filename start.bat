@echo off
chcp 65001 >nul
title Adonis Job Copilot Server
cd /d "%~dp0"

echo =========================================================
echo   Starting Adonis AI Job Copilot Backend & Dashboard
echo =========================================================

if exist "backend\.venv\Scripts\python.exe" (
    echo Using virtual environment python...
    backend\.venv\Scripts\python.exe start.py
) else (
    echo Using system python...
    python start.py
)

pause
