@echo off
rem Installs all plugins without a GUI (Windows, offline). Close IntelliJ IDEA first.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-plugins.ps1" %*
pause
