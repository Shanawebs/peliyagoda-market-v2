#!/usr/bin/env sh
# Bootstrap the pinned official Gradle wrapper JAR if this source-only export does not contain it.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER" ]; then
    echo "Gradle wrapper JAR is not bundled. Fetching pinned Gradle v8.11.1 wrapper..."
    if command -v curl >/dev/null 2>&1; then
        curl -fLsS --retry 3 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -o "$WRAPPER" || exit 1
    else
        echo "Please install curl or add the Gradle 8.11.1 wrapper JAR manually." >&2
        exit 1
    fi
fi
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -Dorg.gradle.appname=gradlew -classpath "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
