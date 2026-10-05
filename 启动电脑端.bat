@echo off
chcp 65001 >nul
title CrossTransfer PC 桌面端
echo ========================================================
echo        CrossTransfer PC - 跨屏快传桌面端
echo ========================================================
echo.
echo [*] 正在启动电脑桌面端独立程序...
echo.

cd /d "%~dp0bin\CrossTransfer_PC"
CrossTransfer_PC.exe