#!/bin/bash
# install.sh — Install the Cameo MCP Bridge
set -e

CAMEO_HOME="${CAMEO_HOME:-D:/DevTools/CatiaMagic}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Cameo MCP Bridge Installer ==="
echo "CAMEO_HOME: $CAMEO_HOME"
echo ""

# Build the Java plugin
echo "Building Java plugin..."
cd "$SCRIPT_DIR/plugin"
./gradlew assemblePlugin
echo "Build complete."
echo ""

# Deploy to Cameo
echo "Deploying plugin to Cameo..."
mkdir -p "$CAMEO_HOME/plugins/com.claude.cameo.bridge"
cp -r build/plugin-dist/com.claude.cameo.bridge/* "$CAMEO_HOME/plugins/com.claude.cameo.bridge/"
echo "Plugin deployed to: $CAMEO_HOME/plugins/com.claude.cameo.bridge/"
echo ""

# Install Python MCP server
echo "Installing Python MCP server..."
cd "$SCRIPT_DIR/mcp-server"
pip install -e . --quiet
echo "Python server installed."
echo ""

# Register with Claude Code
echo "Registering MCP server with Claude Code..."
claude mcp add cameo-bridge --scope user -- python -m cameo_mcp.server
echo ""
echo "=== Installation complete ==="
echo ""
echo "Next steps:"
echo "  1. Restart CATIA Magic"
echo "  2. Open a project"
echo "  3. Start a new Claude Code session"
echo "  4. Say: 'Check cameo status'"
