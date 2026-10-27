from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


pool20 = pd.read_csv("results/threadpool_rps_pool20.csv")
pool50 = pd.read_csv("results/threadpool_rps.csv")

fig, axes = plt.subplots(1, 2, figsize=(12, 5), sharey=True)
lines = (
    ("p50_ms", "p50", "#2563eb"),
    ("p95_ms", "p95", "#ea580c"),
    ("p99_ms", "p99", "#16a34a"),
)

for ax, (data, workers) in zip(axes, ((pool20, 20), (pool50, 50))):
    for column, label, color in lines:
        ax.plot(data["concurrency"], data[column],
                marker="o", linewidth=2, color=color, label=label)

    ax.set_xscale("log")
    ax.set_xticks([10, 50, 100, 500, 1000],
                  labels=["10", "50", "100", "500", "1000"])
    ax.set_title(f"{workers} workers")
    ax.set_xlabel("Concurrent clients")
    ax.grid(alpha=0.3)
    ax.legend()

axes[0].set_ylabel("Latency of successful requests (ms)")
fig.suptitle("Thread pool latency percentiles: 20 vs 50 workers")
fig.tight_layout()

output = Path("results/plots/threadpool_20_vs_50_latency.png")
output.parent.mkdir(parents=True, exist_ok=True)
fig.savefig(output, dpi=180)
plt.close(fig)

print(f"Saved {output}")