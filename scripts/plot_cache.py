from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt

results = pd.read_csv("results/cache_hitratio.csv")

means = results.groupby("mode")[["hit_ratio", "avg_ms"]].mean()
means = means.reindex(["no_cache", "cached"])

baseline = means.loc["no_cache", "avg_ms"]
saving = 100 * (baseline - means.loc["cached", "avg_ms"]) / baseline

fig, axes = plt.subplots(1, 2, figsize=(10, 4))

labels = ["No cache", "LRU cache"]
colors = ["#94a3b8", "#2563eb"]

bars = axes[0].bar(labels, means["hit_ratio"] * 100, color=colors)
axes[0].bar_label(bars, fmt="%.1f%%", padding=4)
axes[0].set_ylabel("Cache hit ratio (%)")
axes[0].set_ylim(0, 110)

bars = axes[1].bar(labels, means["avg_ms"], color=colors)
axes[1].bar_label(bars, fmt="%.3f", padding=4)
axes[1].set_ylabel("Mean response latency (ms)")
axes[1].set_ylim(0, means["avg_ms"].max() * 1.2)

fig.suptitle(
    f"Skewed workload: {saving:.1f}% lower mean latency with caching"
)

fig.tight_layout()

Path("results/plots").mkdir(parents=True, exist_ok=True)
fig.savefig("results/plots/cache.png", dpi=180)
plt.close(fig)

print(f"Latency saving: {saving:.2f}%")
print("Saved results/plots/cache.png")