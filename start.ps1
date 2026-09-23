# Adonis PowerShell Launcher
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  ⚡ Starting Adonis AI Job Copilot Backend & Dashboard" -ForegroundColor Green
Write-Host "=========================================================" -ForegroundColor Cyan

if (Test-Path "backend\.venv\Scripts\python.exe") {
    Write-Host "Using virtual environment: backend\.venv\Scripts\python.exe" -ForegroundColor Gray
    & "backend\.venv\Scripts\python.exe" "start.py"
} else {
    Write-Host "Using system python" -ForegroundColor Gray
    python "start.py"
}
