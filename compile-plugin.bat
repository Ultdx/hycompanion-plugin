@echo off
setlocal
cd /d "%~dp0"
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
        if not "%%a"=="" if not "%%a:~0,1%%"=="#" (
            set "%%a=%%b"
        )
    )
    echo [INFO] Environment loaded.
    echo.
)

:: Check for Sentry configuration
set "SENTRY_BUILD_FLAGS=-Dsentry.skip=false"
if defined SENTRY_AUTH_TOKEN (
    echo [INFO] Sentry authentication token found.
) else (
    set "SENTRY_BUILD_FLAGS=-Dsentry.skip=true"
    echo [WARN] SENTRY_AUTH_TOKEN not set. Sentry source upload will be skipped.
    echo [WARN] Create a .env file or set the environment variable to enable Sentry.
    echo.
)
for %%a in (%*) do if /i "%%~a"=="--skip-sentry" set "SENTRY_BUILD_FLAGS=-Dsentry.skip=true"
if "%SENTRY_BUILD_FLAGS%"=="-Dsentry.skip=true" echo [INFO] Sentry source upload disabled for this build.

:: Install the local server API using the version declared in pom.xml.
echo [1/4] Installing local Hytale Server API...
if not exist "libs\HytaleServer.jar" (
    echo [ERROR] Copy the matching HytaleServer.jar into libs before compiling.
    exit /b 1
)
set "HYTALE_SERVER_VERSION="
for /f "delims=" %%v in ('powershell.exe -NoProfile -Command "([xml](Get-Content -LiteralPath 'pom.xml')).project.properties.'hytale.server.version'"') do set "HYTALE_SERVER_VERSION=%%v"
if not defined HYTALE_SERVER_VERSION (
    echo [ERROR] Hytale Server version not found in pom.xml.
    exit /b 1
)
echo [INFO] Hytale Server API version: %HYTALE_SERVER_VERSION%
:: Run outside the project so build extensions do not resolve the API before installation.
pushd libs
call mvn org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file -q -Dfile=HytaleServer.jar -DgroupId=com.hypixel.hytale -DartifactId=Server -Dversion=%HYTALE_SERVER_VERSION% -Dpackaging=jar -DgeneratePom=true
set "HYTALE_INSTALL_RESULT=%errorlevel%"
popd
if not "%HYTALE_INSTALL_RESULT%"=="0" exit /b 1

:: MVN install dependencies
echo [2/4] Installing dependencies...
call mvn install -q %SENTRY_BUILD_FLAGS%
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   DEPENDENCIES INSTALLATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Clean and compile with Maven
echo [3/4] Compiling...
call mvn compile -q %SENTRY_BUILD_FLAGS%
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   COMPILATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Package the plugin with Sentry auth token if available
echo [4/4] Packaging plugin with dependencies...
call mvn package -DskipTests -q %SENTRY_BUILD_FLAGS%

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
echo Output: target\hycompanion-plugin-*-jar-with-dependencies.jar
echo.

:: Check if called from another script (skip pause)
::if "%1"=="--nopause" exit /b 0
::pause
