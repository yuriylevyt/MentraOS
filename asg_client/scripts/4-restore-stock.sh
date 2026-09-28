#!/usr/bin/env bash
#
# 4-restore-stock.sh - Safely restore stock MentraOS launcher and recovery on Mentra Live
#
# This script disables the custom build and restores the factory stock Mentra client.
#
set -euo pipefail

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

STOCK_PKG="com.mentra.asg_client"
DEV_PKG="com.mentra.asg_client.thirdparty"
RECOVERY_PKG="com.mentra.recovery"
UPDATER_PKG="com.augmentos.otaupdater"

echo -e "${BLUE}=== [4/4] Restoring Stock Mentra Live Client ===${NC}"

# Check ADB device connection
echo -e "${BLUE}Checking ADB connection...${NC}"
DEVICE_COUNT=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | wc -l | tr -d ' ')

if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo -e "${RED}Error: No Mentra Live device connected in 'device' state.${NC}" >&2
  echo "Connect the glasses via USB Infinity Cable and verify with 'adb devices'." >&2
  exit 1
fi

DEVICE_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -n 1)
echo -e "${GREEN}✓ Connected to device: $DEVICE_SERIAL${NC}"

adb root >/dev/null 2>&1 || true

# 1. Stop and disable custom build
echo ""
echo -e "${BLUE}[1/4] Stopping and disabling custom build ($DEV_PKG)...${NC}"
adb shell am force-stop "$DEV_PKG" 2>/dev/null || true
adb shell pm disable-user --user 0 "$DEV_PKG" 2>/dev/null || true
echo -e "${GREEN}✓ Custom build disabled.${NC}"

# 2. Re-enable stock packages
echo ""
echo -e "${BLUE}[2/4] Re-enabling stock Mentra packages...${NC}"
adb shell cmd package install-existing "$STOCK_PKG" 2>/dev/null || true
adb shell pm enable "$STOCK_PKG" 2>/dev/null || true
adb shell pm enable "$RECOVERY_PKG" 2>/dev/null || true
adb shell pm enable "$UPDATER_PKG" 2>/dev/null || true
echo -e "${GREEN}✓ Stock packages re-enabled.${NC}"

# 3. Reset launcher and start stock app
echo ""
echo -e "${BLUE}[3/4] Resetting default launcher to stock...${NC}"
adb shell cmd package set-home-activity --user 0 "$STOCK_PKG/com.mentra.asg_client.MainActivity" 2>/dev/null || true
adb shell am start -n "$STOCK_PKG/com.mentra.asg_client.MainActivity"
echo -e "${GREEN}✓ Stock MainActivity launched.${NC}"

# 4. Verify process
echo ""
echo -e "${BLUE}[4/4] Verifying stock process...${NC}"
sleep 2
PID=$(adb shell pidof "$STOCK_PKG" 2>/dev/null || true)

if [[ -n "$PID" ]]; then
  echo -e "${GREEN}✓ Stock $STOCK_PKG is running (PID: $PID)!${NC}"
else
  echo -e "${YELLOW}Notice: $STOCK_PKG was started; check 'adb shell dumpsys activity' if needed.${NC}"
fi

echo ""
echo -e "${GREEN}================================================================${NC}"
echo -e "${GREEN}✓ Mentra Live is now running stock firmware and client!${NC}"
echo -e "${GREEN}================================================================${NC}"
