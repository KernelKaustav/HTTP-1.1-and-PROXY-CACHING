from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


output_dir = Path("results/plots")
output_dir.mkdir(parents=True, exist_ok=True)

pool50 = pd.read_csv("results/threadpool_rps.csv")
pool20 = pd.read_csv("results/threadpool_rps_pool20.csv")
eventloop = pd.read_csv("results/eventloop_latency.csv")

series = (
    (pool50, "Thread pool (50)", "#2563eb", "o", "-"),
    (pool20, "Thread pool (20)", "#7c3aed", "s", "--"),
    (eventloop, "Event loop", "#dc2626", "^", "-"),
)

ticks = [10, 50, 100, 500, 1000]


def save_comparison(column, ylabel, title, filename):
    fig, ax = plt.subplots(figsize=(9, 5))

    for data, label, color, marker, linestyle in series:
        ax.plot(data["concurrency"], data[column], marker=marker,
                linestyle=linestyle, linewidth=2, color=color, label=label)

    ax.set_xscale("log")
    ax.set_xticks(ticks, labels=[str(value) for value in ticks])
    ax.set_xlabel("Concurrent clients")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(alpha=0.3)
    ax.legend()
    fig.tight_layout()

    path = output_dir / filename
    fig.savefig(path, dpi=180)
    plt.close(fig)
    print(f"Saved {path}")


save_comparison("requests_per_sec", "Requests per second",
                "Throughput vs concurrency", "rps_vs_concurrency.png")
save_comparison("p50_ms", "p50 latency (ms)",
                "Median latency vs concurrency", "p50_comparison.png")
save_comparison("p95_ms", "p95 latency (ms)",
                "p95 latency vs concurrency", "p95_comparison.png")
save_comparison("p99_ms", "p99 latency (ms)",
                "p99 latency vs concurrency", "p99_comparison.png")

fig, axes = plt.subplots(1, 2, figsize=(12, 5), sharey=True)
percentiles = (
    ("p50_ms", "p50", "#2563eb"),
    ("p95_ms", "p95", "#ea580c"),
    ("p99_ms", "p99", "#16a34a"),
)

for ax, (data, workers) in zip(axes, ((pool20, 20), (pool50, 50))):
    for column, label, color in percentiles:
        ax.plot(data["concurrency"], data[column], marker="o",
                linewidth=2, color=color, label=label)

    ax.set_xscale("log")
    ax.set_xticks(ticks, labels=[str(value) for value in ticks])
    ax.set_title(f"{workers} workers")
    ax.set_xlabel("Concurrent clients")
    ax.grid(alpha=0.3)
    ax.legend()

axes[0].set_ylabel("Latency of successful requests (ms)")
fig.suptitle("Thread pool latency percentiles: 20 vs 50 workers")
fig.tight_layout()

path = output_dir / "threadpool_20_vs_50_latency.png"
fig.savefig(path, dpi=180)
plt.close(fig)
print(f"Saved {path}")