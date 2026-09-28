#!/usr/bin/env bash
#
# 1-build-asg.sh - Build custom asg_client APK with WebRTC degradationPreference support
#
set -euo pipefail

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve ASG_DIR
if [[ -d "${ASG_CLIENT_DIR:-}" ]]; then
  ASG_DIR="$ASG_CLIENT_DIR"
elif [[ -d "$SCRIPT_DIR/../../../../MentraOS/asg_client" ]]; then
  ASG_DIR="$(cd "$SCRIPT_DIR/../../../../MentraOS/asg_client" && pwd)"
elif [[ -d "$SCRIPT_DIR/../../../MentraOS/asg_client" ]]; then
  ASG_DIR="$(cd "$SCRIPT_DIR/../../../MentraOS/asg_client" && pwd)"
elif [[ -d "$SCRIPT_DIR/../../MentraOS/asg_client" ]]; then
  ASG_DIR="$(cd "$SCRIPT_DIR/../../MentraOS/asg_client" && pwd)"
elif [[ -f "$SCRIPT_DIR/../build.gradle" ]]; then
  ASG_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  echo -e "${RED}Error: Cannot locate MentraOS/asg_client directory.${NC}" >&2
  echo "Please set ASG_CLIENT_DIR=/path/to/MentraOS/asg_client" >&2
  exit 1
fi

echo -e "${BLUE}=== [1/4] Building Custom asg_client (Debug APK) ===${NC}"
echo -e "Location: ${YELLOW}$ASG_DIR${NC}"
echo ""

cd "$ASG_DIR"

if [[ ! -f "$ASG_DIR/.env" && -f "$ASG_DIR/.env.example" ]]; then
  cp "$ASG_DIR/.env.example" "$ASG_DIR/.env"
fi

if [[ ! -d "$ASG_DIR/StreamPackLite/core" ]]; then
  echo -e "${YELLOW}Initializing StreamPackLite submodule...${NC}"
  git submodule update --init --recursive
fi

echo -e "${BLUE}Running ./gradlew assembleDebug...${NC}"
./gradlew assembleDebug -PASG_VERSION_CODE="${ASG_VERSION_CODE:-301019000}" -PASG_VERSION_NAME="${ASG_VERSION_NAME:-301019000-dev}"

APK_PATH="$ASG_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ -f "$APK_PATH" ]]; then
  APK_SIZE=$(ls -lh "$APK_PATH" | awk '{print $5}')
  echo ""
  echo -e "${GREEN}✓ Build Succeeded!${NC}"
  echo -e "APK Path: ${YELLOW}$APK_PATH${NC}"
  echo -e "Size:     ${GREEN}$APK_SIZE${NC}"
  echo ""
  echo -e "Next step: Run ${YELLOW}./2-install-asg.sh${NC} to install on connected Mentra Live glasses."
else
  echo -e "${RED}✗ Error: APK was not found at $APK_PATH${NC}" >&2
  exit 1
fi
