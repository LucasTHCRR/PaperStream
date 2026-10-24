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
