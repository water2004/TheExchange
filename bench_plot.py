"""Read saturation benchmark CSV and write bench_report.png."""
import csv
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

scenarios = ["partitioned", "random", "sameSlot"]
data = {scenario: {"in_flight": [], "rate": [], "success": [], "p99": []}
        for scenario in scenarios}

with open("bench_data.csv") as file:
    for row in csv.DictReader(file):
        values = data[row["scenario"]]
        values["in_flight"].append(int(row["in_flight"]))
        values["rate"].append(float(row["rate_s"]))
        values["success"].append(float(row["successRate"]))
        values["p99"].append(float(row["result_p99_us"]) / 1000.0)

fig, axes = plt.subplots(1, 3, figsize=(18, 5))
concurrency_levels = sorted({value for scenario in scenarios
                             for value in data[scenario]["in_flight"]})
colors = {"partitioned": "#2ecc71", "random": "#3498db", "sameSlot": "#e74c3c"}
labels = {
    "partitioned": "independent player warehouses",
    "random": "random slots (one warehouse)",
    "sameSlot": "same slot (worst contention)",
}
markers = {"partitioned": "s", "random": "o", "sameSlot": "^"}

for scenario in scenarios:
    values = data[scenario]
    axes[0].plot(values["in_flight"], values["rate"], color=colors[scenario],
                 marker=markers[scenario], label=labels[scenario], linewidth=2, markersize=7)
    axes[1].plot(values["in_flight"], values["p99"], color=colors[scenario],
                 marker=markers[scenario], label=labels[scenario], linewidth=2, markersize=7)
    axes[2].plot(values["in_flight"], values["success"], color=colors[scenario],
                 marker=markers[scenario], label=labels[scenario], linewidth=2, markersize=7)

for axis in axes:
    axis.set_xscale("log", base=2)
    axis.set_xticks(concurrency_levels)
    axis.set_xticklabels([str(value) for value in concurrency_levels])
    axis.set_xlabel("transactions in flight", fontsize=11)
    axis.grid(True, alpha=0.3)

axes[0].set_ylabel("throughput (transactions/s)", fontsize=11)
axes[0].set_title("V2 Saturation Throughput", fontsize=14)
axes[0].yaxis.set_major_formatter(ticker.FuncFormatter(
    lambda value, _: f"{value / 1000:.0f}K" if value < 1_000_000 else f"{value / 1_000_000:.1f}M"))
axes[0].legend(fontsize=8, loc="best")

axes[1].set_ylabel("RESULT latency P99 (ms)", fontsize=11)
axes[1].set_title("Tail Latency", fontsize=14)

axes[2].set_ylabel("optimistic commit success (%)", fontsize=11)
axes[2].set_title("Commit Success Rate", fontsize=14)
axes[2].set_ylim(-5, 105)
axes[2].yaxis.set_major_formatter(ticker.FuncFormatter(lambda value, _: f"{value:.0f}%"))

plt.tight_layout()
plt.savefig("bench_report.png", dpi=150, bbox_inches="tight")
print("Saved bench_report.png")
