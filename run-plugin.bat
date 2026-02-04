@echo off
title Hycompanion Plugin - Interactive Test Mode
echo.
echo ========================================
echo   Hycompanion Plugin Launcher
echo ========================================
echo.

:: Set JAVA_HOME to Java 25 (Adoptium)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot

:: Check if --skip-compile flag is passed
if "%1"=="--skip-compile" goto run

:: Compile the plugin first
echo [Step 1] Compiling plugin...
echo.
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   COMPILATION FAILED!
    echo ========================================
    echo Check the errors above and fix them.
    pause
    exit /b 1
)
echo.
echo Compilation successful!
echo.

:run
echo [Step 2] Running plugin in test mode...
echo.

:: JVM flags for module access (required for Gson, Socket.IO with Java 25)
set JVM_FLAGS=--enable-preview
set JVM_FLAGS=%JVM_FLAGS% --add-opens java.base/java.lang=ALL-UNNAMED
set JVM_FLAGS=%JVM_FLAGS% --add-opens java.base/java.lang.reflect=ALL-UNNAMED
set JVM_FLAGS=%JVM_FLAGS% --add-opens java.base/java.util=ALL-UNNAMED

:: Run the plugin
"%JAVA_HOME%\bin\java" %JVM_FLAGS% -jar target\hycompanion-plugin-1.0.0-SNAPSHOT-jar-with-dependencies.jar

pause
