#!/bin/bash

# Quick script to run test server with banner
# Usage: ./run-test-server.sh [ServerClass]
#
# Examples:
#   ./run-test-server.sh TestServer
#   ./run-test-server.sh AdvancedTestServer
#   ./run-test-server.sh WebSocketOnlyServer
#   ./run-test-server.sh HttpOnlyServer

SERVER_CLASS=${1:-TestServer}

echo "Compiling..."
mvn compile test-compile -q

echo ""
echo "Starting $SERVER_CLASS..."
echo "Press Ctrl+C to stop"
echo ""

java -cp target/classes:target/test-classes com.akakata.$SERVER_CLASS
