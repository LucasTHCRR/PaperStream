# Paper vs PaperStream

![Benchmark](benchmark.png)

## Correction, 2026-11-13

The first version of this page (2026-11-07) had wrong flight numbers. The flight test moved a chunk window with plugin tickets. That loads chunks on the main thread, which a real player never does, and the "max MSPT" was a 5 second average. The numbers 91 against 11.7 ms per tick and spikes of 800 against 52 ms came from that and are withdrawn.

The flight is now measured with a real client that flies through the world. The pregeneration numbers are measured again without the warm-up phase. The old raw data is kept in `results-old-method.jsonl`.

## Setup

- One Pterodactyl server with 6 cores, Java 25, the same machine for every run
- Paper 26.3 build 246 against PaperStream 26.3 1.0.0-BETA, once with the default profile `medium` and once with `performance-beta`
- Every run starts with a fresh world: seed 12345, view distance 10, simulation distance 8, no plugins except the measuring plugin
- All settings are the defaults, `worker-threads: -1` (auto)
- 3 rounds, variants alternating, the tables show the average

Tests:

1. **Pregeneration**: generate 2,025 chunks with 64 requests at the same time. The first 600 chunks are warm-up and not counted.
2. **Fast flight**: a real client flies in a straight line at 80 blocks per second for 30 seconds, first through new land, then after a restart through the same, now saved land.
3. **Elytra flight**: the same at 33 blocks per second.

"Chunks ahead" is how many of the 11 chunks in front of the player (view distance 10 plus the own chunk) are loaded. "No new chunks" is the share of the flight where not a single chunk in front of the player was loaded.

## Results

Fast flight, 80 blocks per second:

| | Paper | PaperStream medium | PaperStream performance-beta |
|---|---|---|---|
| Worker threads (auto) | 1 | 4 | 2 |
| New land, chunks ahead (of 11) | 1.1 | **10.9** | 10.6 |
| New land, share of the flight with no new chunks | 97% | **0%** | **0%** |
| New land, ticks over 50 ms | 13.7 | **0.7** | 1.3 |
| New land, longest tick | 110 ms | 65 ms | **50 ms** |
| New land, avg ms per tick | **6.0** | 14.5 | 13.9 |
| New land, CPU | **49.7 s** | 81.3 s | 75.0 s |
| Saved land, chunks ahead (of 11) | 8.4 | **11.0** | **11.0** |
| Saved land, avg ms per tick | 12.4 | 8.7 | **8.6** |
| Saved land, CPU | 52.5 s | 29.4 s | **27.8 s** |
| RAM after the flight | **1.83 GB** | 2.67 GB | 2.73 GB |
| World on disk | **80 MB** | 109 MB | 113 MB |

Elytra flight, 33 blocks per second:

| | Paper | PaperStream medium | PaperStream performance-beta |
|---|---|---|---|
| New land, chunks ahead (of 11) | 10.4 | **11.0** | **11.0** |
| New land, avg ms per tick | 8.9 | 9.1 | **7.7** |
| New land, CPU | 44.0 s | 43.2 s | **40.6 s** |
| Saved land, CPU | 19.0 s | 16.4 s | **15.6 s** |

Pregeneration:

| | Paper | PaperStream medium | PaperStream performance-beta |
|---|---|---|---|
| Chunks per second after warm-up | 60 | **185** | 125 |
| Chunks per second including warm-up | 57 | **160** | 112 |
| Worker CPU per chunk | 16.6 ms | 16.6 ms | **15.1 ms** |
| Avg MSPT while generating | **2.1** | 6.3 | 3.8 |

Raw numbers per run are in `flight-fast.jsonl`, `flight-elytra.jsonl` and `pregen.jsonl`.

RAM is covered separately in [RAM.md](RAM.md).

## What this means

Where PaperStream is better:

- Flying fast through new land: the chunks in front of the player are there. Paper does not keep up at this speed, the player flies into nothing for 97% of the flight.
- Fewer lag spikes during that flight: about 1 tick over 50 ms instead of 14.
- Pregeneration is three times as fast with `medium`, at the same CPU cost per chunk.
- Flying through saved land needs 44 to 47% less CPU.

Where it is not, or where it needs context:

- In new land PaperStream uses more CPU and has a higher average tick time than Paper (14.5 against 6.0 ms). That is the price for generating the chunks the player needs. Paper is cheaper there because it generates far fewer chunks in the same time.
- At normal Elytra speed both keep up and the difference is small.
- A large part of the lead while generating comes from more worker threads. Paper uses only a part of the cores by default. A Paper admin can raise `worker-threads` and gets some of the difference back. This benchmark shows the defaults.
- During pregeneration the MSPT is higher with `medium` (6.3 instead of 2.1 ms), because more chunks are generated at once. At 20 TPS this is not noticeable, but it is measurable.
- RAM right after the fast flight is about 45% higher, because many more chunks were loaded.
- The world is about 37% larger. PaperStream writes new chunks with LZ4 instead of zlib (`region-compression: lz4` in `paperstream.yml`). LZ4 compresses less but loads much faster, which is part of the lower CPU when loading saved chunks. Set it to `deflate` to save disk space.

Limits of this test:

- One player, few mobs, no redstone. Older measurements for those are in the main README.
- One machine with 6 cores, 3 rounds.
- Measured with 1.0.0-BETA, not with later versions.

## Scripts

`run_benchmark.py` and `chunkbench-plugin/` are the scripts of the first version. Their pregeneration test is still valid, their flight test is the one that was withdrawn. The script for the flight with a real client is not in the repository yet. `python3 chart.py` draws `benchmark.png` from the three raw data files.
