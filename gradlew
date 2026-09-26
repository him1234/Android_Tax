#!/bin/sh
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  if command -v gradle >/dev/null 2>&1; then
    exec gradle -p "$DIR" "$@"
  fi
  echo "Gradle wrapper JAR is absent from the original source archive. Install Gradle 8.11.1, then run 'gradle wrapper --gradle-version 8.11.1' in this folder." >&2
  exit 1
fi
exec java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
