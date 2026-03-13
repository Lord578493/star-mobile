@echo off
setlocal EnableDelayedExpansion
title TG Bypass - Android APK Build

echo.
echo  ================================================
echo   TG Bypass - Android APK Build
echo  ================================================
echo.

set "ROOT=%~dp0"
set "TOOLS=%ROOT%build_tools"
set "JDK_DIR=%TOOLS%\jdk17"
set "SDK_DIR=%TOOLS%\android_sdk"
set "GRADLE_DIR=%TOOLS%\gradle"
set "TEMP_DIR=%TOOLS%\temp"
set "PS_HELPER=%ROOT%download_helper.ps1"

if not exist "%TOOLS%"    mkdir "%TOOLS%"
if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

if not exist "%PS_HELPER%" (
    echo  ERROR: download_helper.ps1 not found next to build.bat!
    pause & exit /b 1
)

:: ============================================================
:: STEP 1: JDK 17
:: ============================================================
echo  [1/4] Checking JDK 17...
echo  ------------------------------------------------

if exist "%JDK_DIR%\bin\java.exe" (
    echo  [OK] JDK 17 already installed
    goto :step2
)

set "JDK_ZIP=%TEMP_DIR%\jdk17.zip"
powershell -ExecutionPolicy Bypass -File "%PS_HELPER%" -Url "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10+7/OpenJDK17U-jdk_x64_windows_hotspot_17.0.10_7.zip" -Out "%JDK_ZIP%" -Name "JDK 17" -MinMB 170
if !errorlevel! neq 0 (
    echo  ERROR: Failed to download JDK 17
    pause & exit /b 1
)

echo  Extracting JDK 17...
if exist "%TOOLS%\jdk17_tmp" rmdir /s /q "%TOOLS%\jdk17_tmp"
powershell -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%JDK_ZIP%' -DestinationPath '%TOOLS%\jdk17_tmp' -Force"
if !errorlevel! neq 0 (
    echo  ERROR: Failed to extract JDK!
    pause & exit /b 1
)
for /d %%d in ("%TOOLS%\jdk17_tmp\jdk*") do move "%%d" "%JDK_DIR%" > nul 2>&1
rmdir /s /q "%TOOLS%\jdk17_tmp" 2>nul
del /f /q "%JDK_ZIP%" 2>nul

if not exist "%JDK_DIR%\bin\java.exe" (
    echo  ERROR: java.exe not found after extraction!
    pause & exit /b 1
)
echo  [OK] JDK 17 installed

:step2
set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JDK_DIR%\bin;%PATH%"
echo.

:: ============================================================
:: STEP 2: Android SDK
:: ============================================================
echo  [2/4] Checking Android SDK...
echo  ------------------------------------------------

set "ANDROID_JAR=%SDK_DIR%\platforms\android-34\android.jar"
set "SDKMANAGER=%SDK_DIR%\cmdline-tools\cmdline-tools\bin\sdkmanager.bat"

if exist "%ANDROID_JAR%" (
    echo  [OK] Android SDK already installed
    goto :step3
)

set "SDK_ZIP=%TEMP_DIR%\cmdtools.zip"
powershell -ExecutionPolicy Bypass -File "%PS_HELPER%" -Url "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -Out "%SDK_ZIP%" -Name "Android SDK Tools" -MinMB 100
if !errorlevel! neq 0 (
    echo  ERROR: Failed to download Android SDK Tools
    pause & exit /b 1
)

echo  Extracting Android SDK Tools...
if exist "%SDK_DIR%\cmdline-tools" rmdir /s /q "%SDK_DIR%\cmdline-tools"
powershell -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%SDK_ZIP%' -DestinationPath '%SDK_DIR%\cmdline-tools' -Force"
del /f /q "%SDK_ZIP%" 2>nul

if not exist "%SDKMANAGER%" (
    echo  ERROR: sdkmanager.bat not found after extraction!
    echo  Expected: %SDKMANAGER%
    pause & exit /b 1
)

echo  Pre-accepting all licenses (no prompts)...
set "LIC_DIR=%SDK_DIR%\licenses"
if not exist "%LIC_DIR%" mkdir "%LIC_DIR%"
echo 8933bad161af4178b1185d1a37fbf41ea5269c55>  "%LIC_DIR%\android-sdk-license"
echo d56f5187479451eabf01fb78af6dfcb131a6481e>> "%LIC_DIR%\android-sdk-license"
echo 24333f8a63b6825ea9c5514f83c2829b004d1fee>> "%LIC_DIR%\android-sdk-license"
echo 84831b9409646a918e30573bab4c9c91346d8abd>> "%LIC_DIR%\android-sdk-preview-license"
echo 33b6a2b64607f11b759f320ef9dff4ae5c47d97a>> "%LIC_DIR%\google-gdk-license"
set "ANDROID_HOME=%SDK_DIR%"

echo  Installing Android Platform 34 and Build Tools (~80 MB)...
"%SDKMANAGER%" --sdk_root="%SDK_DIR%" "platforms;android-34" "build-tools;34.0.0"
if !errorlevel! neq 0 (
    echo  ERROR: sdkmanager failed!
    pause & exit /b 1
)

if not exist "%ANDROID_JAR%" (
    echo  ERROR: android.jar not found after SDK install!
    pause & exit /b 1
)
echo  [OK] Android SDK installed

:step3
set "ANDROID_HOME=%SDK_DIR%"
echo.

:: ============================================================
:: STEP 3: Gradle
:: ============================================================
echo  [3/4] Checking Gradle...
echo  ------------------------------------------------

set "GRADLE_BIN=%GRADLE_DIR%\bin\gradle.bat"

if exist "%GRADLE_BIN%" (
    echo  [OK] Gradle already installed
    goto :step4
)

set "GRADLE_ZIP=%TEMP_DIR%\gradle.zip"
powershell -ExecutionPolicy Bypass -File "%PS_HELPER%" -Url "https://services.gradle.org/distributions/gradle-8.9-bin.zip" -Out "%GRADLE_ZIP%" -Name "Gradle 8.9" -MinMB 120
if !errorlevel! neq 0 (
    echo  ERROR: Failed to download Gradle
    pause & exit /b 1
)

echo  Extracting Gradle...
if exist "%TOOLS%\gradle_tmp" rmdir /s /q "%TOOLS%\gradle_tmp"
powershell -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%TOOLS%\gradle_tmp' -Force"
for /d %%d in ("%TOOLS%\gradle_tmp\gradle*") do move "%%d" "%GRADLE_DIR%" > nul 2>&1
rmdir /s /q "%TOOLS%\gradle_tmp" 2>nul
del /f /q "%GRADLE_ZIP%" 2>nul

if not exist "%GRADLE_BIN%" (
    echo  ERROR: gradle.bat not found after extraction!
    pause & exit /b 1
)
echo  [OK] Gradle 8.9 installed

:step4
echo.

:: ============================================================
:: STEP 4: Build APK
:: ============================================================
echo  [4/4] Building APK...
echo  ------------------------------------------------

set "JAVA_HOME=%JDK_DIR%"
set "ANDROID_HOME=%SDK_DIR%"
set "PATH=%JDK_DIR%\bin;%GRADLE_DIR%\bin;%PATH%"

:: Write local.properties with forward slashes (required by Gradle)
set "SDK_FWD=%SDK_DIR:\=/%"
echo sdk.dir=%SDK_FWD%> "%ROOT%local.properties"

cd /d "%ROOT%"

set "GRADLE_LOG=%TEMP_DIR%\gradle_build.log"
if exist "%GRADLE_LOG%" del /f /q "%GRADLE_LOG%"

echo  Starting compilation...
echo  (First run downloads ~300 MB of dependencies, 5-15 min)
echo  All output is shown in real time below:
echo.

:: Run Gradle - output goes to screen AND log file simultaneously
"%GRADLE_BIN%" assembleDebug --no-daemon --console=plain --info 2>&1 | powershell -ExecutionPolicy Bypass -Command "
    $tasks = 0
    $logPath = '%GRADLE_LOG%'
    $logFile = [IO.StreamWriter]::new($logPath, $false, [Text.Encoding]::UTF8)
    $input | ForEach-Object {
        $line = $_
        $logFile.WriteLine($line)
        $logFile.Flush()
        if ($line -match '^> Task') {
            $tasks++
            $pct = [math]::Min(95, $tasks * 2)
            $filled = [int]($pct / 5)
            $bar = ('=' * $filled) + ('.' * (20 - $filled))
            $task = $line -replace '> Task :app:',''
            Write-Host ('  [' + $bar + '] ' + $pct + '%%  ' + $task)
        } elseif ($line -match 'Downloading') {
            Write-Host ('  >> ' + $line)
        } elseif ($line -match 'BUILD SUCCESSFUL') {
            Write-Host '  [====================] 100%%  Build successful!' -ForegroundColor Green
        } elseif ($line -match 'BUILD FAILED') {
            Write-Host '  BUILD FAILED!' -ForegroundColor Red
        } elseif ($line -match 'FAILURE:') {
            Write-Host ('  ' + $line) -ForegroundColor Red
        } elseif ($line -match '^\* What went wrong') {
            Write-Host ('  ' + $line) -ForegroundColor Red
        } elseif ($line -match 'error:') {
            Write-Host ('  ERROR: ' + $line) -ForegroundColor Red
        }
    }
    $logFile.Close()
"

:: errorlevel here is from PowerShell, not Gradle - check APK instead
set "APK=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo.
    echo  ================================================
    echo  BUILD FAILED - APK not created
    echo.
    echo  Last 30 lines from log:
    powershell -ExecutionPolicy Bypass -Command "if (Test-Path '%GRADLE_LOG%') { Get-Content '%GRADLE_LOG%' | Select-String 'error|FAILED|Exception|What went wrong' | Select-Object -Last 30 }"
    echo.
    echo  Full log saved to: %GRADLE_LOG%
    echo  ================================================
    pause & exit /b 1
)

if not exist "output" mkdir output
copy /Y "%APK%" "output\TG_Bypass.apk" > nul

echo.
echo  ================================================
echo   SUCCESS!
echo.
echo   APK: output\TG_Bypass.apk
echo.
echo   How to install on phone:
echo    1. Copy TG_Bypass.apk to phone
echo    2. Open the file on phone
echo    3. Allow install from unknown sources
echo    4. Tap Install
echo  ================================================
echo.
explorer output
pause
