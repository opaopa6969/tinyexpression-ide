#!/bin/sh
# TinyExpression IDE — startup script for systemd
# Uses Maven-generated classpath for all runtime dependencies
set -e

JAVA_HOME="/home/opa/.sdkman/candidates/java/21.0.9-oracle"
JAVA="$JAVA_HOME/bin/java"
MAVEN_HOME="/home/opa/.sdkman/candidates/maven/3.9.9"
MAVEN="$MAVEN_HOME/bin/mvn"

HOME_DIR="/home/opa/tinyexpression-ide"
cd "$HOME_DIR"

# Generate classpath from Maven (runtime scope only)
CP_FILE="$HOME_DIR/target/classpath.txt"
"$MAVEN" -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$CP_FILE" -f "$HOME_DIR/pom.xml" 2>/dev/null

CP="target/classes:$(cat "$CP_FILE")"

exec "$JAVA" -Xmx512m -XX:+ExitOnOutOfMemoryError -cp "$CP" org.unlaxer.tinyexpression.ide.IdeMain
