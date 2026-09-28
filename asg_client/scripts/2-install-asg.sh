#!/usr/bin/env bash
#
# 2-install-asg.sh - Safely install custom asg_client.thirdparty on Mentra Live
#
# This script disables stock Mentra packages and installs your custom build
# under com.mentra.asg_client.thirdparty without modifying MTK or BES firmware.
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
  exit 1
fi

APK_PATH="$ASG_DIR/app/build/outputs/apk/debug/app-debug.apk"

STOCK_PKG="com.mentra.asg_client"
DEV_PKG="com.mentra.asg_client.thirdparty"
RECOVERY_PKG="com.mentra.recovery"
UPDATER_PKG="com.augmentos.otaupdater"

echo -e "${BLUE}=== [2/4] Installing Custom asg_client on Mentra Live ===${NC}"

# Check APK exists
if [[ ! -f "$APK_PATH" ]]; then
  echo -e "${YELLOW}APK not found at $APK_PATH.${NC}"
  echo -e "Running ${BLUE}1-build-asg.sh${NC} first..."
  "$SCRIPT_DIR/1-build-asg.sh"
fi

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

# Ensure adb root
adb root >/dev/null 2>&1 || true

# 1. Disable stock packages safely
echo ""
echo -e "${BLUE}[1/5] Disabling stock packages...${NC}"
adb shell am force-stop "$RECOVERY_PKG" 2>/dev/null || true
adb shell pm disable-user --user 0 "$RECOVERY_PKG" 2>/dev/null || true

adb shell am force-stop "$UPDATER_PKG" 2>/dev/null || true
adb shell pm disable-user --user 0 "$UPDATER_PKG" 2>/dev/null || true

adb shell am force-stop "$STOCK_PKG" 2>/dev/null || true
adb shell pm disable-user --user 0 "$STOCK_PKG" 2>/dev/null || true
echo -e "${GREEN}✓ Stock packages disabled.${NC}"

# 2. Install custom build
echo ""
echo -e "${BLUE}[2/5] Installing custom APK ($DEV_PKG)...${NC}"
adb install -r -d -g "$APK_PATH"
echo -e "${GREEN}✓ Installed successfully.${NC}"

# 3. Grant runtime permissions
echo ""
echo -e "${BLUE}[3/5] Granting runtime permissions...${NC}"
PERMS=(
  "android.permission.CAMERA"
  "android.permission.RECORD_AUDIO"
  "android.permission.ACCESS_FINE_LOCATION"
  "android.permission.ACCESS_COARSE_LOCATION"
  "android.permission.ACCESS_BACKGROUND_LOCATION"
  "android.permission.BLUETOOTH"
  "android.permission.BLUETOOTH_ADMIN"
  "android.permission.BLUETOOTH_CONNECT"
  "android.permission.BLUETOOTH_SCAN"
  "android.permission.BLUETOOTH_ADVERTISE"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.WRITE_EXTERNAL_STORAGE"
  "android.permission.READ_MEDIA_IMAGES"
  "android.permission.READ_MEDIA_VIDEO"
)

for p in "${PERMS[@]}"; do
  adb shell pm grant "$DEV_PKG" "$p" 2>/dev/null || true
done

# Ignore battery optimizations
adb shell dumpsys deviceidle whitelist +"$DEV_PKG" 2>/dev/null || true
# Clear any stale OTA session from development package so it does not resume old production OTA
adb shell rm -f "/data/data/$DEV_PKG/shared_prefs/ota_session.xml" 2>/dev/null || true
echo -e "${GREEN}✓ Permissions granted.${NC}"

# 4. Set as default launcher and start
echo ""
echo -e "${BLUE}[4/5] Setting as default launcher and starting...${NC}"
adb shell cmd package set-home-activity --user 0 "$DEV_PKG/com.mentra.asg_client.MainActivity" 2>/dev/null || true
adb shell am start -n "$DEV_PKG/com.mentra.asg_client.MainActivity"

# 5. Verify process is active
echo ""
echo -e "${BLUE}[5/5] Verifying process status...${NC}"
sleep 2
PID=$(adb shell pidof "$DEV_PKG" 2>/dev/null || true)

if [[ -n "$PID" ]]; then
  echo -e "${GREEN}✓ $DEV_PKG is running (PID: $PID)!${NC}"
else
  echo -e "${YELLOW}Warning: Process PID not detected immediately, check logcat.${NC}"
fi

echo ""
echo -e "${GREEN}================================================================${NC}"
echo -e "${GREEN}✓ Deployment Complete!${NC}"
echo -e "To monitor degradationPreference & streaming logs in real time, run:"
echo -e "  ${YELLOW}./3-monitor-stream.sh${NC}"
echo ""
echo -e "To revert back to stock Mentra Live anytime, run:"
echo -e "  ${YELLOW}./4-restore-stock.sh${NC}"
echo -e "${GREEN}================================================================${NC}"
