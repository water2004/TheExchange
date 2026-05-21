"""Read bench_data.csv and write bench_report.png."""
import csv
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

data = {"dedicated": ([], [], []), "random": ([], [], []), "sameSlot": ([], [], [])}

with open("bench_data.csv") as f:
    for row in csv.DictReader(f):
        s = row["scenario"]
        data[s][0].append(int(row["core_threads"]))
        data[s][1].append(float(row["rate_s"]))
        data[s][2].append(float(row["successRate"]))

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))

colors = {"dedicated": "#2ecc71", "random": "#3498db", "sameSlot": "#e74c3c"}
labels = {"dedicated": "no contention (dedicated slots)", "random": "random slots (54 shared)", "sameSlot": "full contention (same slot)"}
markers = {"dedicated": "s", "random": "o", "sameSlot": "^"}

for s in ["dedicated", "random", "sameSlot"]:
    t, r, _ = data[s]
    ax1.plot(t, r, color=colors[s], marker=markers[s], label=labels[s], linewidth=2, markersize=8)

ax1.set_xlabel("core_threads", fontsize=12)
ax1.set_ylabel("throughput (ops/s)", fontsize=12)
ax1.set_title("Realistic Throughput (submit → coreExecutor)", fontsize=14)
ax1.legend(fontsize=9, loc="upper left")
ax1.grid(True, alpha=0.3)
ax1.set_xlim(left=0)
ax1.yaxis.set_major_formatter(ticker.FuncFormatter(lambda y, _: f'{y/1000:.0f}K' if y < 1_000_000 else f'{y/1_000_000:.1f}M'))

for s in ["dedicated", "random", "sameSlot"]:
    t, _, sr = data[s]
    ax2.plot(t, sr, color=colors[s], marker=markers[s], label=labels[s], linewidth=2, markersize=8)

ax2.set_xlabel("core_threads", fontsize=12)
ax2.set_ylabel("success rate (%)", fontsize=12)
ax2.set_title("Success Rate vs core_threads", fontsize=14)
ax2.legend(fontsize=9, loc="lower left")
ax2.grid(True, alpha=0.3)
ax2.set_xlim(left=0)
ax2.set_ylim(-5, 105)
ax2.yaxis.set_major_formatter(ticker.FuncFormatter(lambda y, _: f'{y:.0f}%'))

plt.tight_layout()
plt.savefig("bench_report.png", dpi=150, bbox_inches="tight")
print("Saved bench_report.png")
