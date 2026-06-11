#!/bin/sh
APP_HOME=`cd "$(dirname "$0")" && pwd`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

exec "$JAVACMD" -Xmx512m -Xms64m \
  "-Dorg.gradle.appname=$(basename $0)" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
