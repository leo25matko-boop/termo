@echo off
setlocal
cd /d "%~dp0"
if not exist "%~dp0Instaliraj.ps1" (
  echo Prvo raspakiraj cijeli ZIP opcijom Izdvoji sve, zatim pokreni ovu datoteku.
  pause
  exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Instaliraj.ps1"
if errorlevel 1 (
  pause
  exit /b 1
)
exit /b 0
