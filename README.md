# PaperStream

A Paper fork for Minecraft 26.3, 26.2 and 1.21.11 that uses less CPU, loads chunks faster and protects your server from lag machines.

**NOT OFFICIAL PAPER / not affiliated with PaperMC.** Please report problems to PaperStream, not to PaperMC: https://dc.gg/paperstream

PaperStream is a drop-in replacement for Paper. Your worlds, plugins and configs keep working. Every change PaperStream makes has its own switch in `paperstream.yml`, so you can turn off anything you do not want.

> Status: alpha. Built for Minecraft 26.3 (branch `ver/26.3`, Java 25), 26.2 (branch `main`, Java 25) and 1.21.11 (branch `ver/1.21.11`, Java 21). More versions are planned.

Documentation: [Wiki](https://github.com/LucasTHCRR/PaperStream/wiki)

## Features

### Chunk loading

- **Directional loading**: chunks in front of a player load and get sent before the ones behind. Flying with an elytra, the world appears where you are heading.
- **Generation boost**: fast moving players may generate more chunks at once, as long as the world is not lagging.
- **Faster region files**: new chunks are written with LZ4 instead of zlib, which is much cheaper to read back. Old chunks stay readable, and Paper and vanilla read LZ4 too, so switching back is always possible.
- **Unload delay and rate**: chunks a player just left stay loaded for 30 seconds (Paper: 10), so turning around does not read them from disk again. The unload rate per tick can be set as well.
- **Fresh chunks**: block entities, leaf updates and mob spawning in a chunk that was just loaded and has no player close by wait a few seconds. Mobs loaded from the world file follow the activation range at once instead of ticking fully for their first 10 seconds.
- **More chunk workers**: with `worker-threads: -1`, PaperStream uses all cores but two for chunk loading and generation (Paper uses a quarter).
- **Chunk send budget**, **prefetch** and **fast travel** (smaller simulation distance while flying fast) are available but off by default.

### Entities

- **Idle mob throttling**: idle mobs far away from every player tick less often. Fighting, ridden, leashed, hurt or falling mobs are never throttled.
- **Villagers**: villagers that stand still run their AI less often. Trading, sleeping or hurt villagers, villagers in danger and villagers near an iron farm's golem are never throttled.
- **Pathfinding budget**: new path calculations per world and tick are limited. A mob that hits the limit just tries again a moment later.
- **Item piles**: more than 64 dropped items in one block tick only once per second. Nothing is removed, picking up, hoppers and despawning keep working.
- **Stack guard**: no more than 8 armor stands, minecarts or boats of one kind in one block (96 per chunk). Only placing is refused, nothing that exists is removed. Plugins and commands are never limited.

### World

- **Redstone clock guard**: a chunk with more than 400 redstone updates per second for 10 seconds in a row gets slowed down. Nothing is broken, ops get a message with the coordinates. Farm clocks and piston doors never reach the limit.
- **Explosion limit**: at most 32 explosions per chunk and tick, the rest is dropped.
- **Lag protection**: a world that averages more than 45 ms per tick gets half the pathfinding budget and spawns mobs only every second tick, for that world only.
- **Idle mode**: with nobody online, worlds tick once per second. Plugins keep running normally.
- **Per world MSPT**: `/paperstream mspt` shows how long each world takes per tick.

### Profiles

One line in `paperstream.yml` sets everything above at once:

| Profile | For |
|---|---|
| `low` | close to Paper, only changes nobody notices in game |
| `medium` | balanced, the default |
| `performance` | weak servers with 2 to 4 cores, saves the most CPU, chunks load a bit slower |
| `custom` | nothing is changed, every value comes from the file |

Switch live with `/paperstream profile <name>`. A profile writes its values into the file once, every value can still be changed by hand afterwards.

### Coming from Paper

- `/paperstream migrate` compares your Paper, Spigot and Bukkit configs with what PaperStream recommends (Alternate Current redstone, cheaper collisions, entity save limits, item merging, ...) and shows the differences. `/paperstream migrate apply confirm` takes them over. Make a backup of `config/` and `spigot.yml` first, PaperStream does not make one by itself.
- After the start, a plugin check lists plugins that failed to start and plugins that do the same job as a PaperStream feature.

## Commands

All commands need `paperstream.command` (ops by default).

| Command | What it does |
|---|---|
| `/paperstream info` | TPS, MSPT, RAM and what PaperStream is doing right now |
| `/paperstream mspt` | milliseconds per tick for each world |
| `/paperstream pregen <world> <radius in chunks> [x z]` | generate chunks ahead of time, pauses while the server lags (`stop`, `status`) |
| `/paperstream reload` | reload `paperstream.yml` |
| `/paperstream reload properties` | apply `server.properties` changes live (motd, max players, whitelist, difficulty, view and simulation distance, pvp, ...) |
| `/paperstream profile [name]` | show or switch the profile |
| `/paperstream migrate [apply confirm]` | compare your configs with the recommendations |

## Measured

Numbers from local test servers with bots, Minecraft 26.2.

| Test | Result |
|---|---|
| 1,000 unmergeable items in one block, player next to it | 5.1 / 4.2 ms per tick with item piles off, 2.0 / 2.0 ms with it on. A hopper below still collects. |
| 128 observer clocks in one chunk, about 1,700 updates per second | slowed down after 10 seconds, a single farm clock next to it keeps running |
| 14 minecarts placed on one rail, 14 armor stands shot from a dispenser | 8 of each are placed, `/summon` still works |

## Downloads

Server jars are on the [releases page](https://github.com/LucasTHCRR/PaperStream/releases):

| Minecraft | Jar | Java |
|---|---|---|
| 26.3 | `paperstream-26.3-alpha.1.jar` | 25 |
| 26.2 | `paperstream-26.2-alpha.1.jar` | 25 |
| 1.21.11 | `paperstream-1.21.11-alpha.1.jar` | 21 |

## Building

You need Java 25.

```
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar ends up in `paperstream-server/build/libs/`.

## PaperProxy

PaperStream can pair with [PaperProxy](https://github.com/LucasTHCRR/PaperProxy), a Velocity fork, so the proxy moves players to another server before this one restarts. The link is off by default and every part of it has its own switch in `paperstream.yml`.

## License

PaperStream's own code and patches are licensed under the GNU General Public License v3.0, see [LICENSE](LICENSE). Paper, Spigot and CraftBukkit code keeps its original license.

Made by LucasTHCR. Built on [Paper](https://papermc.io), with thanks to the PaperMC team and everyone behind Spigot and CraftBukkit.

Discord: https://dc.gg/paperstream
