@echo off
title Hycompanion Plugin - Compile
echo.
echo ========================================
echo   Hycompanion Plugin Compiler
echo ========================================
echo.

:: Set JAVA_HOME to Java 25 (Adoptium)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot

echo Using Java: %JAVA_HOME%
echo.

:: Load environment variables from .env file if it exists
if exist ".env" (
    echo [INFO] Loading environment from .env file...
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        :: Skip comments and empty lines
        echo %%a | findstr /b "#" >nul && continue
        if not "%%a"=="" (
            set "%%a=%%b"
        )
    )
    echo [INFO] Environment loaded.
    echo.
)

:: Check for Sentry configuration
if defined SENTRY_AUTH_TOKEN (
    echo [INFO] Sentry authentication token found.
) else (
    echo [WARN] SENTRY_AUTH_TOKEN not set. Sentry source upload will be skipped.
    echo [WARN] Create a .env file or set the environment variable to enable Sentry.
    echo.
)

:: MVN install dependencies
echo [1/3] Installing dependencies...
call mvn install -q
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   DEPENDENCIES INSTALLATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Clean and compile with Maven
echo [2/3] Compiling...
call mvn compile -q
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   COMPILATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Package the plugin with Sentry auth token if available
echo [3/3] Packaging plugin with dependencies...
if defined SENTRY_AUTH_TOKEN (
    call mvn package -DskipTests -q -Denv.SENTRY_AUTH_TOKEN=%SENTRY_AUTH_TOKEN%
) else (
    call mvn package -DskipTests -q
)

if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   PACKAGING FAILED!
    echo ========================================
    pause
    exit /b 1
)

echo.
echo ========================================
echo   BUILD SUCCESSFUL!
echo ========================================
echo.
echo Output: target\hycompanion-plugin-1.1.3-SNAPSHOT-jar-with-dependencies.jar
echo.

:: Check if called from another script (skip pause)
::if "%1"=="--nopause" exit /b 0
::pause
