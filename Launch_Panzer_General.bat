@echo off
echo Starting Android Emulator (Medium_Phone_API_37.0)...
start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_37.0
echo Waiting for emulator to finish booting...
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" wait-for-device
:waitloop
for /f "tokens=*" %%a in ('"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%a
if not "%BOOT%"=="1" (
    timeout /t 2 /nobreak >nul
    goto waitloop
)
echo Installing latest Panzer General v1.5 APK...
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r "%~dp0app\build\outputs\apk\debug\PanzerGeneral-debug.apk"
echo Launching Panzer General v1.5...
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" shell am start -n com.ssi.panzergeneral/.MainActivity
echo Done!
