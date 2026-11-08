# RAM, Paper vs PaperStream

Same setup as the [main benchmark](README.md): AMD EPYC Milan with 6 cores, Paper 26.3 build 244 against PaperStream 1.0.0-BETA, seed 12345, a fresh world per run, 2 rounds. Measured with `/chunkbench mem` after two `System.gc()` calls. Raw data: `ram-results.jsonl`, script: `run_ram.py`.

## Heap after GC

| When | Paper | PaperStream medium | PaperStream performance-beta |
|---|---|---|---|
| Idle after startup | 253 MB | 257 MB | 253 MB |
| After pregenerating 961 chunks | 272 MB | 277 MB | 370 MB |
| Right after the fast flight | 303 MB | 497 MB | 618 MB |
| 90 seconds later | **301 MB** | **303 MB** | **301 MB** |

## Process memory and GC

| | Paper | PaperStream medium | PaperStream performance-beta |
|---|---|---|---|
| Process memory at the end | 1,725 MB | 1,696 MB | 1,852 MB |
| Committed heap at the end | 1,149 MB | 1,174 MB | 1,208 MB |
| Metaspace | 157 MB | 157 MB | 161 MB |
| Memory allocated during the flight | 8.6 GB | 9.4 GB | 9.4 GB |
| Total GC time | 2.34 s | 1.66 s | 1.99 s |

## What this means

- **No leak.** 90 seconds after the flight all three are back at about 300 MB.
- **More heap right after the flight is on purpose.** PaperStream keeps chunks a player just left loaded for 30 seconds instead of 10, so turning around does not read them from disk again. That costs 200 to 300 MB for a short time and is freed afterwards.
- **performance-beta is higher after pregeneration** (370 instead of 272 MB). Back to the same value once the chunks are unloaded. We are looking into it.
- **About 9% more memory allocated during the flight**, but 15 to 29% less GC time. With 2 rounds the GC time is noisy (Paper alone varies between 0.5 and 1.0 s per flight).
- **Process memory** is the same for `medium`. `performance-beta` is about 7% higher because the JVM committed more heap.

PaperStream does not need more RAM than Paper. It uses more for a short time to keep chunks loaded and gives all of it back.
