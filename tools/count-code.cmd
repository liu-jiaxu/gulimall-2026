@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0count-code.ps1" %*
if "%~1"=="" pause
endlocal
