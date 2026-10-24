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
echo "Installed ${SERVER_JARFILE} (${TAG})"
