@echo off
echo Compiling the USB camera module with the configured JDK 17 toolchain...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" -Tasks :camera-usb:compileUsbOnlyDebugKotlin
exit /b %errorlevel%
