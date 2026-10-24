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
