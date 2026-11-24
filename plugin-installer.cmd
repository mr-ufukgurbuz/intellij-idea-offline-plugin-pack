@echo off
rem Starts the graphical plugin installer (Windows, offline). Double-click this file.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0plugin-installer.ps1" %*
if errorlevel 1 pause
