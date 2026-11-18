#!/bin/bash
# Written by the PaperStream egg. The panel's start command only runs this file.
if [ "${AUTO_UPDATE}" = "1" ] && [ -f paperstream-update.sh ]; then
  sh paperstream-update.sh
fi
FLAGS=""
if [ "${AIKAR_FLAGS}" = "1" ]; then
  FLAGS="-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"
fi
# Java 25+ AOT cache: the first start records which classes the server loads, later starts are about 20% faster.
# Recorded again after a jar or Java update or when the JVM flags change. Turn off with AOT_CACHE=0.
AOT=""
if [ "${AOT_CACHE:-1}" = "1" ] && java -XX:+PrintFlagsFinal -version 2>/dev/null | grep -q " AOTCacheOutput "; then
  CACHE=paperstream.aot
  SIG="${MAX_RAM_PERCENT:-90.0} ${FLAGS} ${JVM_ARGS}"
  JBIN=$(readlink -f "$(command -v java)")
  if [ -f "${CACHE}" ] && [ "${CACHE}" -nt "${SERVER_JARFILE:-server.jar}" ] && [ "${CACHE}" -nt "${JBIN}" ] && [ "$(cat "${CACHE}.flags" 2>/dev/null)" = "${SIG}" ]; then
    AOT="-XX:AOTCache=${CACHE}"
  else
    rm -f "${CACHE}" "${CACHE}.flags"
    echo "${SIG}" > "${CACHE}.flags"
    AOT="-XX:AOTCacheOutput=${CACHE}"
    echo "[PaperStream] Recording the Java startup cache during this run, the next start will be faster."
  fi
fi
# exec, so Java gets the console input and the panel's stop command directly.
exec java -Xms128M -XX:MaxRAMPercentage="${MAX_RAM_PERCENT:-90.0}" ${FLAGS} ${AOT} ${JVM_ARGS} -Dterminal.jline=false -Dterminal.ansi=true -jar "${SERVER_JARFILE:-server.jar}" --nogui
