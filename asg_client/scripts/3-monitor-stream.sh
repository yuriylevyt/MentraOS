#!/usr/bin/env bash
#
# 3-monitor-stream.sh - Monitor WebRTC degradationPreference & streaming logs on Mentra Live
#
# Usage:
#   ./3-monitor-stream.sh         # Tail live streaming logs
#   ./3-monitor-stream.sh --clear # Clear logcat buffer first, then tail
#
set -euo pipefail

# Colors
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

if [[ "${1:-}" == "--clear" || "${1:-}" == "-c" ]]; then
  echo -e "${YELLOW}Clearing logcat buffer...${NC}"
  adb logcat -c
  echo -e "${GREEN}✓ Cleared.${NC}"
fi

echo -e "${BLUE}=== [3/4] Monitoring WebRTC & WHIP Streaming on Mentra Live ===${NC}"
echo -e "Waiting for stream events (Ctrl+C to stop)..."
echo -e "Look for: ${CYAN}degradation=MAINTAIN_RESOLUTION${NC} vs ${YELLOW}degradation=MAINTAIN_FRAMERATE${NC}"
echo ""

adb logcat -v threadtime | grep -E --color=always \
  "Applied video bitrate|degradation=|degradationPreference|WhipStreamingService|WhipStreamConfig|WhipBitratePolicy|StreamCommandHandler|VideoQuality|start_stream"
