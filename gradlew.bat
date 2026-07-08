@echo off
REM Gradle wrapper - uses cached Gradle 9.2.0

set GRADLE_BIN=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat

if not exist "%GRADLE_BIN%" (
    echo [ERROR] Gradle 9.2.0 cache not found: %GRADLE_BIN%
    echo Please build once with IntelliJ IDEA or VS Code Gradle Extension.
    exit /b 1
)

if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set.
    echo Example: set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot
    exit /b 1
)

"%GRADLE_BIN%" %*
