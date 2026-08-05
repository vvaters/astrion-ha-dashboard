@echo off
REM ============================================================
REM  Astrion HA Dashboard - one-click restore
REM
REM  Put your built APK next to this file and name it
REM  astrion-remote.apk, then double-click.
REM
REM  BEFORE running: on the remote, enable Developer Mode
REM  (Settings > System > About Phone > tap Build Number 7x),
REM  then Developer options > USB debugging ON, plug in USB,
REM  and accept the "Allow USB debugging" popup.
REM ============================================================
setlocal

REM Find adb: PATH first, then the usual platform-tools locations.
set ADB=adb
where adb >nul 2>&1
if errorlevel 1 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
    ) else if exist "%USERPROFILE%\AndroidSdk\platform-tools\adb.exe" (
        set ADB=%USERPROFILE%\AndroidSdk\platform-tools\adb.exe
    ) else (
        echo ERROR: adb not found. Install Android platform-tools and add it to PATH,
        echo or edit the ADB variable at the top of this script.
        pause
        exit /b 1
    )
)

set APK=%~dp0astrion-remote.apk
if not exist "%APK%" (
    echo ERROR: astrion-remote.apk not found next to this script.
    echo Build it first: gradlew.bat assembleRelease
    echo Then copy app\build\outputs\apk\release\app-release.apk here as astrion-remote.apk
    pause
    exit /b 1
)

echo.
echo Checking for the remote...
"%ADB%" devices | findstr /r "device$" >nul
if errorlevel 1 (
    echo.
    echo ERROR: No device found. Is USB debugging enabled and the popup accepted?
    pause
    exit /b 1
)

echo Installing app...
"%ADB%" install -r "%APK%" || goto :fail

echo Granting permissions...
"%ADB%" shell pm grant com.astrion.remote android.permission.READ_EXTERNAL_STORAGE
"%ADB%" shell pm grant com.astrion.remote android.permission.WRITE_EXTERNAL_STORAGE
"%ADB%" shell pm grant com.astrion.remote android.permission.RECORD_AUDIO
"%ADB%" shell pm grant com.astrion.remote android.permission.WRITE_SECURE_SETTINGS

echo Setting as home app + enabling Home-button rescue...
"%ADB%" shell cmd package set-home-activity com.astrion.remote/.MainActivity
"%ADB%" shell settings put secure enabled_accessibility_services com.astrion.remote/com.astrion.remote.input.KeyRescueService
"%ADB%" shell settings put secure accessibility_enabled 1

echo Disabling the firmware's over-sensitive wake gestures...
"%ADB%" shell settings put secure wake_gesture_enabled 0
"%ADB%" shell settings put secure double_tap_to_wake 0

echo Enabling hold-power-to-sleep...
"%ADB%" shell dpm set-active-admin com.astrion.remote/.input.SleepAdminReceiver

echo Pre-compiling for speed (takes ~30s)...
"%ADB%" shell cmd package compile -m speed -f com.astrion.remote

echo Launching...
"%ADB%" shell am start -n com.astrion.remote/.MainActivity

echo.
echo DONE. On the remote: if a permissions screen appears, tap CONFIRM.
echo The app rebuilds its config, floorplan, and icons by itself on first launch.
pause
exit /b 0

:fail
echo.
echo Install failed - see message above.
pause
exit /b 1
