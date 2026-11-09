@echo off
rem Build entry point (public version).
rem
rem Java 21 (Temurin). Newer JDKs bundled with recent Android Studio releases are
rem rejected by this project's Gradle Kotlin DSL.
rem
rem Requires JAVA_HOME and ANDROID_HOME in the environment. Nothing here is
rem machine-specific: the script builds from its own folder, so the project can
rem live anywhere.

if "%JAVA_HOME%"=="" (
    echo [build] JAVA_HOME is not set. Point it at a JDK 21 installation.
    exit /b 1
)
if "%ANDROID_HOME%"=="" (
    if not "%ANDROID_SDK_ROOT%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
)
if "%ANDROID_HOME%"=="" (
    echo [build] ANDROID_HOME is not set. Point it at your Android SDK.
    exit /b 1
)

cd /d "%~dp0"

rem Optional: route Gradle's build directory outside the project. Needed when the
rem project lives in a synced folder (Google Drive, OneDrive), where the resource
rem merger fails with AccessDeniedException on intermediate files. Point
rem SD_INIT_SCRIPT at an init script that overrides the build directory.
if not "%SD_INIT_SCRIPT%"=="" if exist "%SD_INIT_SCRIPT%" (
    call gradlew.bat -I "%SD_INIT_SCRIPT%" %*
    exit /b %ERRORLEVEL%
)

call gradlew.bat %*
