import json, os
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
rows = [json.loads(l) for l in open(HERE + '/results.jsonl')]
VARIANTS = ['paper', 'ps-medium', 'ps-beta']

def avg(variant, path):
    vals = []
    for r in rows:
        if r['variant'] != variant:
            continue
        v = r
        for p in path:
            v = v[p]
        vals.append(v)
    return sum(vals) / len(vals)

def series(path): return [avg(v, path) for v in VARIANTS]

BG = '#0f1419'; CARD = '#161d26'; TXT = '#e6edf3'; MUT = '#8b98a5'; ACC = '#2dd4bf'
COLORS = ['#6e7681', '#2dd4bf', '#60a5fa']
LABELS = ['Paper', 'PS medium', 'PS perf-beta']

flight = series(['sweepNew', 'avgMspt'])
spikes = series(['sweepNew', 'maxMspt'])
pregen = series(['pregen', 'cps'])
saved = series(['sweepSaved', 'cpu'])
panels = [
    ('Fast flight through new land', 'avg ms per tick, lower is better', flight, '{:.1f} ms',
     '{:.0f}% lower'.format((1 - min(flight[1:]) / flight[0]) * 100)),
    ('Worst tick during that flight', 'max ms per tick, lower is better', spikes, '{:.0f} ms',
     '{:.0f}x smaller spikes'.format(spikes[0] / min(spikes[1:]))),
    ('Pregeneration speed', 'chunks per second, higher is better', pregen, '{:.1f}',
     '{:.1f}x faster'.format(max(pregen[1:]) / pregen[0])),
    ('Flight through saved land', 'CPU seconds used, lower is better', saved, '{:.1f} s',
     'up to {:.0f}% less CPU'.format((1 - min(saved[1:]) / saved[0]) * 100)),
]

fig = plt.figure(figsize=(14, 8.9), dpi=150, facecolor=BG)
fig.text(0.04, 0.94, 'PaperStream vs Paper', color=TXT, fontsize=28, weight='bold')
fig.text(0.04, 0.90, 'Minecraft 26.3  ·  Paper build 244  vs  PaperStream 1.0.0-BETA  ·  same machine, same seed, default settings',
         color=MUT, fontsize=12)
for i, (title, sub, vals, fmt, badge) in enumerate(panels):
    r, c = divmod(i, 2)
    x0 = 0.04 + c * 0.48
    y0 = 0.53 - r * 0.39
    card = fig.add_axes([x0, y0, 0.44, 0.34])
    card.set_facecolor(CARD); card.set_xticks([]); card.set_yticks([])
    for s in card.spines.values():
        s.set_visible(False)
    card.text(0.04, 0.86, title, color=TXT, fontsize=15, weight='bold', transform=card.transAxes)
    card.text(0.04, 0.76, sub, color=MUT, fontsize=10.5, transform=card.transAxes)
    card.text(0.96, 0.86, badge, color=ACC, fontsize=12, weight='bold', ha='right', transform=card.transAxes)
    ax = fig.add_axes([x0 + 0.13, y0 + 0.03, 0.25, 0.21])
    ax.set_facecolor(CARD)
    y = [2, 1, 0]
    m = max(vals)
    ax.barh(y, vals, color=COLORS, height=0.62)
    ax.set_xlim(0, m * 1.28)
    ax.set_yticks(y); ax.set_yticklabels(LABELS, color=TXT, fontsize=11)
    ax.set_xticks([])
    for s in ax.spines.values():
        s.set_visible(False)
    ax.tick_params(length=0)
    for yy, vv in zip(y, vals):
        ax.text(vv + m * 0.02, yy, fmt.format(vv), va='center', color=TXT, fontsize=11, weight='bold')

world = series(['worldMB'])
fig.text(0.04, 0.035,
         '6 cores, Java 25, fresh world per run (seed 12345, view distance 10), no players, {} rounds averaged. '
         'Flight = 17x17 chunk window moving 1 chunk every 4 ticks for 150 chunks.\n'
         'Default worker-threads: Paper uses 1 here, PaperStream 4 (medium) / 2 (performance-beta). '
         'PaperStream stores chunks with LZ4 instead of zlib: faster to load,\n'
         'but the world is larger ({:.0f} MB vs {:.0f} MB). Set region-compression: deflate to save space. '
         'Script and raw data: benchmarks/ in the PaperStream repository.'.format(
             len(rows) // 3, world[1], world[0]),
         color=MUT, fontsize=9.5, linespacing=1.6)
fig.savefig(HERE + '/benchmark.png', facecolor=BG)
