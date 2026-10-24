#!/bin/ash
# PaperStream install script (Pterodactyl). Downloads the PaperStream server jar from GitHub releases.
apk add --no-cache curl jq >/dev/null

mkdir -p /mnt/server
cd /mnt/server || exit 1

API="https://api.github.com/repos/LucasTHCRR/PaperStream/releases?per_page=100"
RELEASES=/tmp/paperstream-releases.json
curl -fsSL "${API}" > "${RELEASES}" || { echo "Could not reach GitHub."; exit 1; }

if [ -z "${PAPERSTREAM_BUILD}" ] || [ "${PAPERSTREAM_BUILD}" = "latest" ]; then
  # Newest release for the chosen Minecraft version, alpha builds included.
  TAG=$(jq -r --arg mc "${MINECRAFT_VERSION}" '[.[] | select(.tag_name | startswith($mc + "-"))][0].tag_name // empty' "${RELEASES}")
else
  TAG="${PAPERSTREAM_BUILD}"
fi
if [ -z "${TAG}" ]; then
  echo "No PaperStream release found for Minecraft ${MINECRAFT_VERSION}. Available:"
  jq -r '.[].tag_name' "${RELEASES}"
  exit 1
fi

URL=$(jq -r --arg tag "${TAG}" '.[] | select(.tag_name == $tag) | .assets[] | select(.name | test("^paperstream-.*\\.jar$")) | .browser_download_url' "${RELEASES}" | head -n 1)
if [ -z "${URL}" ]; then
  echo "Release ${TAG} has no PaperStream jar."
  exit 1
fi

echo "Downloading PaperStream ${TAG}"
curl -fL -o "${SERVER_JARFILE}" "${URL}" || exit 1
basename "${URL}" > .paperstream-installed
echo "Installed ${SERVER_JARFILE} (${TAG})"

# The start command runs this before every start when "Auto update" is on.
cat > paperstream-update.sh <<'UPDATE'
#!/bin/sh
# Written by the PaperStream egg. Runs before every start when "Auto update" is on:
# installs the newest PaperStream build for this Minecraft version, unless a build is pinned.
MC="${MINECRAFT_VERSION}"
JAR="${SERVER_JARFILE:-server.jar}"
if [ -n "${PAPERSTREAM_BUILD}" ] && [ "${PAPERSTREAM_BUILD}" != "latest" ]; then
  exit 0
fi
JSON=$(curl -fsSL --max-time 15 "https://api.github.com/repos/LucasTHCRR/PaperStream/releases?per_page=100") || {
  echo "[PaperStream] Update check failed, starting the installed build."
  exit 0
}
URL=$(printf '%s' "${JSON}" | grep -o "\"browser_download_url\": *\"[^\"]*/paperstream-${MC}-[^\"]*\.jar\"" | head -n 1 | sed 's/.*"\(https[^"]*\)"$/\1/')
if [ -z "${URL}" ]; then
  echo "[PaperStream] No build found for Minecraft ${MC}, starting the installed build."
  exit 0
fi
NEW=$(basename "${URL}")
OLD=$(cat .paperstream-installed 2>/dev/null)
if [ "${NEW}" = "${OLD}" ] && [ -f "${JAR}" ]; then
  echo "[PaperStream] ${NEW} is up to date."
  exit 0
fi
echo "[PaperStream] Updating ${OLD:-unknown} -> ${NEW}"
if curl -fsSL --max-time 300 -o "${JAR}.download" "${URL}"; then
  mv "${JAR}.download" "${JAR}" && echo "${NEW}" > .paperstream-installed
else
  rm -f "${JAR}.download"
  echo "[PaperStream] Download failed, starting the installed build."
fi
UPDATE
chmod +x paperstream-update.sh

# The panel's start command runs this file.
cat > paperstream-start.sh <<'START'
#!/bin/bash
# Written by the PaperStream egg. The panel's start command only runs this file.
if [ "${AUTO_UPDATE}" = "1" ] && [ -f paperstream-update.sh ]; then
  sh paperstream-update.sh
fi
FLAGS=""
if [ "${AIKAR_FLAGS}" = "1" ]; then
  FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"
fi
# exec, so Java gets the console input and the panel's stop command directly.
exec java -Xms128M -XX:MaxRAMPercentage="${MAX_RAM_PERCENT:-90.0}" ${FLAGS} ${JVM_ARGS} -Dterminal.jline=false -Dterminal.ansi=true -jar "${SERVER_JARFILE:-server.jar}" --nogui
START
chmod +x paperstream-start.sh
