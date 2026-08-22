#!/bin/sh
# TinyExpression IDE — startup script for systemd
# Builds classpath from local Maven repo + compiled classes
set -e

HOME_DIR="/home/opa/tinyexpression-ide"
cd "$HOME_DIR"

# Build classpath: compiled classes + Maven dependency jars
CP="target/classes"
MAVEN_REPO="/home/opa/.m2/repository"

# Collect all runtime dependency JARs from local Maven repo
for jar in \
  "$MAVEN_REPO/org/unlaxer/unlaxer-common/2.8.0/unlaxer-common-2.8.0.jar" \
  "$MAVEN_REPO/org/unlaxer/unlaxer-dsl/2.8.0/unlaxer-dsl-2.8.0.jar" \
  "$MAVEN_REPO/org/eclipse/lsp4j/org.eclipse.lsp4j/0.23.1/org.eclipse.lsp4j-0.23.1.jar" \
  "$MAVEN_REPO/org/eclipse/lsp4j/org.eclipse.lsp4j.jsonrpc/0.23.1/org.eclipse.lsp4j.jsonrpc-0.23.1.jar" \
  "$MAVEN_REPO/org/eclipse/jetty/jetty-server/11.0.20/jetty-server-11.0.20.jar" \
  "$MAVEN_REPO/org/eclipse/jetty/jetty-servlet/11.0.20/jetty-servlet-11.0.20.jar" \
  "$MAVEN_REPO/org/eclipse/jetty/websocket/websocket-jetty-server/11.0.20/websocket-jetty-server-11.0.20.jar" \
  "$MAVEN_REPO/org/eclipse/jetty/websocket/websocket-jetty-api/11.0.20/websocket-jetty-api-11.0.20.jar" \
  "$MAVEN_REPO/org/eclipse/jetty/websocket/websocket-servlet/11.0.20/websocket-servlet-11.0.20.jar" \
  "$MAVEN_REPO/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" \
  "$MAVEN_REPO/org/slf4j/slf4j-simple/2.0.9/slf4j-simple-2.0.9.jar"; do
  if [ -f "$jar" ]; then
    CP="$CP:$jar"
  fi
done

exec java -cp "$CP" org.unlaxer.tinyexpression.ide.IdeMain
