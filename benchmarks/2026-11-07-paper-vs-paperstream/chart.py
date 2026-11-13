#!/usr/bin/env python3
import json, os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
VARIANTS = ['paper', 'ps-medium', 'ps-beta']


def series(name, path):
    rows = [json.loads(l) for l in open(os.path.join(HERE, name)) if l.strip()]
    out = []
    for v in VARIANTS:
        vals = []
        for r in rows:
            if r['variant'] != v:
                continue
            x = r
            for k in path:
                x = x[k]
            vals.append(x)
        out.append(sum(vals) / len(vals))
    return out


BG = '#0f1419'; CARD = '#161d26'; TXT = '#e6edf3'; MUT = '#8b98a5'; ACC = '#2dd4bf'
COLORS = ['#6e7681', '#2dd4bf', '#60a5fa']
LABELS = ['Paper', 'PS medium', 'PS perf-beta']

ahead = series('flight-fast.jsonl', ['flyNew', 'aheadAvg'])
spikes = series('flight-fast.jsonl', ['flyNew', 'over50'])
pregen = series('pregen.jsonl', ['gen', 'gen', 'genCps'])
saved = series('flight-fast.jsonl', ['flySaved', 'cpu'])
panels = [
    ('Fast flight through new land', 'chunks ready ahead of the player (of 11), higher is better', ahead, '{:.1f}',
     'Paper flies into the void'),
    ('Lag spikes during that flight', 'ticks over 50 ms in 30 s, lower is better', spikes, '{:.1f}',
     '{:.0f} down to {:.0f}'.format(spikes[0], round(min(spikes[1:])))),
    ('Pregeneration speed', 'chunks per second after warm-up, higher is better', pregen, '{:.0f}',
     '{:.1f}x faster'.format(max(pregen[1:]) / pregen[0])),
    ('Fast flight through saved land', 'CPU seconds used, lower is better', saved, '{:.1f} s',
     'up to {:.0f}% less CPU'.format((1 - min(saved[1:]) / saved[0]) * 100)),
]

fig = plt.figure(figsize=(14, 8.9), dpi=150, facecolor=BG)
fig.text(0.04, 0.94, 'PaperStream vs Paper', color=TXT, fontsize=28, weight='bold')
fig.text(0.04, 0.90, 'Minecraft 26.3  ·  Paper build 246  vs  PaperStream 1.0.0-BETA  ·  same machine, same seed, default settings',
         color=MUT, fontsize=12)
for i, (title, sub, vals, fmt, badge) in enumerate(panels):
    col, row = i % 2, i // 2
    card = fig.add_axes([0.04 + col * 0.47, 0.50 - row * 0.37, 0.45, 0.34], facecolor=CARD)
    card.set_xticks([]); card.set_yticks([])
    for s in card.spines.values():
        s.set_visible(False)
    card.text(0.04, 0.86, title, color=TXT, fontsize=15, weight='bold', transform=card.transAxes)
    card.text(0.04, 0.76, sub, color=MUT, fontsize=10.5, transform=card.transAxes)
    card.text(0.96, 0.86, badge, color=ACC, fontsize=12, weight='bold', ha='right', transform=card.transAxes)
    ax = fig.add_axes([0.04 + col * 0.47 + 0.10, 0.50 - row * 0.37 + 0.03, 0.31, 0.21], facecolor=CARD)
    m = max(vals)
    ys = [2, 1, 0]
    ax.barh(ys, vals, color=COLORS, height=0.62)
    ax.set_xlim(0, m * 1.25)
    ax.set_yticks(ys); ax.set_yticklabels(LABELS, color=TXT, fontsize=11)
    ax.set_xticks([])
    ax.tick_params(length=0)
    for s in ax.spines.values():
        s.set_visible(False)
    for yy, vv in zip(ys, vals):
        ax.text(vv + m * 0.02, yy, fmt.format(vv), va='center', color=TXT, fontsize=11, weight='bold')

fig.text(0.04, 0.035,
         '6 cores, Java 25, fresh world per run (seed 12345, view distance 10), 3 rounds averaged. Flight = a real client moving 80 blocks per second for 30 s.\n'
         'In new land PaperStream generates the chunks the player needs and uses more CPU for that (81 s vs 50 s). Paper does not keep up and the player sees no new chunks 97% of the time.\n'
         'Default worker-threads: Paper uses 1 here, PaperStream 4 (medium) / 2 (performance-beta). At Elytra speed (33 blocks per second) both keep up.\n'
         'Raw data: benchmarks/ in the PaperStream repository.',
         color=MUT, fontsize=9.5, linespacing=1.6)
fig.savefig(HERE + '/benchmark.png', facecolor=BG)
