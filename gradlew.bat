@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
    echo Getting Gradle 8.11.1 wrapper JAR from the pinned official Gradle tag...
    powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER%'"
    if errorlevel 1 (
        echo Could not download Gradle wrapper. Check your internet connection.
        exit /b 1
    )
)
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
