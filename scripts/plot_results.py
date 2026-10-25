"""Plots for the thread-pool vs event-loop comparison.
Run from the repo root:  python scripts/plot_results.py
Reads results/threadpool_rps.csv (pool 50), results/threadpool_rps_pool20.csv
and results/eventloop_rps.csv. Missing files are skipped, not fatal."""
import os
import pandas as pd
import matplotlib.pyplot as plt

OUT = "results/plots"
os.makedirs(OUT, exist_ok=True)
plt.rcParams.update({"figure.dpi": 150, "axes.spines.top": False,
                     "axes.spines.right": False, "axes.grid": True,
                     "grid.linestyle": "--", "grid.alpha": 0.4})

def load(path):
    return pd.read_csv(path) if os.path.exists(path) else None

tp50 = load("results/threadpool_rps.csv")
tp20 = load("results/threadpool_rps_pool20.csv")
el = load("results/eventloop_rps.csv")
series = [(tp50, "Thread-pool (50)", "#2563eb", "o", "-"),
          (tp20, "Thread-pool (20)", "#93c5fd", "s", "--"),
          (el, "Event-loop", "#dc2626", "^", "-")]

def plot(column, ylabel, title, fname, log=False):
    fig, ax = plt.subplots(figsize=(9, 5))
    for df, label, color, marker, ls in series:
        if df is None:
            continue
        ax.plot(df["concurrency"], df[column], marker=marker, ls=ls,
                lw=2, color=color, label=label)
    ax.set_xscale("log")
    ax.set_xticks([10, 50, 100, 500, 1000])
    ax.set_xticklabels(["10", "50", "100", "500", "1000"])
    if log:
        ax.set_yscale("log")
    ax.set_xlabel("Concurrent clients")
    ax.set_ylabel(ylabel)
    ax.set_title(title, fontweight="bold")
    ax.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(OUT, fname))
    plt.close()
    print("Saved", os.path.join(OUT, fname))

plot("requests_per_sec", "Requests / second",
     "Throughput vs concurrency: thread-pool vs event-loop", "rps_vs_concurrency.png")
plot("p50_ms", "p50 latency (ms)", "Median latency", "p50_comparison.png")
plot("p95_ms", "p95 latency (ms)", "p95 latency", "p95_comparison.png")
plot("p99_ms", "p99 latency (ms)", "p99 tail latency", "p99_comparison.png")

# percentile spread, one panel per model
frames = [(tp50, "Thread-pool (50)"), (el, "Event-loop")]
frames = [(d, n) for d, n in frames if d is not None]
fig, axes = plt.subplots(1, len(frames), figsize=(6 * len(frames), 4.5), sharey=True)
if len(frames) == 1:
    axes = [axes]
for ax, (df, name) in zip(axes, frames):
    for col, lab in (("p50_ms", "p50"), ("p95_ms", "p95"), ("p99_ms", "p99")):
        ax.plot(df["concurrency"], df[col], marker="o", lw=2, label=lab)
    ax.set_xscale("log")
    ax.set_xticks([10, 50, 100, 500, 1000])
    ax.set_xticklabels(["10", "50", "100", "500", "1000"])
    ax.set_title(name, fontweight="bold")
    ax.set_xlabel("Concurrent clients")
    ax.legend()
axes[0].set_ylabel("Latency (ms)")
plt.tight_layout()
plt.savefig(os.path.join(OUT, "latency_percentiles.png"))
plt.close()
print("Saved", os.path.join(OUT, "latency_percentiles.png"))