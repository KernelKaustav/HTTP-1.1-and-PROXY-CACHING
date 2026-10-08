import os
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

CSV_POOL50  = "results/threadpool_rps.csv"
CSV_POOL20  = "results/threadpool_rps_pool20.csv"
OUT_DIR     = "results/plots"
os.makedirs(OUT_DIR, exist_ok=True)
df50 = pd.read_csv(CSV_POOL50)
df20 = pd.read_csv(CSV_POOL20)
plt.rcParams.update({
    "figure.dpi":       150,
    "font.family":      "DejaVu Sans",
    "axes.spines.top":  False,
    "axes.spines.right":False,
    "axes.grid":        True,
    "grid.linestyle":   "--",
    "grid.alpha":       0.4,
})
fig, ax = plt.subplots(figsize=(9, 5))
ax.plot(df50["concurrency"], df50["requests_per_sec"],
        marker="o", linewidth=2, color="#2563eb", label="Pool size = 50")
ax.plot(df20["concurrency"], df20["requests_per_sec"],
        marker="s", linewidth=2, color="#dc2626", label="Pool size = 20",
        linestyle="--")
ax.set_xlabel("Concurrent clients", fontsize=11)
ax.set_ylabel("Requests / second", fontsize=11)
ax.set_title("Thread-Pool Server: Throughput vs Concurrency", fontsize=13, fontweight="bold")
ax.legend(fontsize=10)
ax.xaxis.set_major_formatter(ticker.FuncFormatter(lambda x, _: f"{int(x):,}"))
ax.yaxis.set_major_formatter(ticker.FuncFormatter(lambda x, _: f"{int(x):,}"))
for _, row in df50.iterrows():
    ax.annotate(f"{row['requests_per_sec']:.0f}",
                xy=(row["concurrency"], row["requests_per_sec"]),
                xytext=(0, 8), textcoords="offset points",
                ha="center", fontsize=8, color="#2563eb")

plt.tight_layout()
out = os.path.join(OUT_DIR, "rps_vs_concurrency.png")
plt.savefig(out)
plt.close()
print(f"Saved: {out}")
fig, ax = plt.subplots(figsize=(9, 5))
ax.plot(df50["concurrency"], df50["p50_ms"],
        marker="o", linewidth=2, color="#16a34a", label="p50 (median)")
ax.plot(df50["concurrency"], df50["p95_ms"],
        marker="s", linewidth=2, color="#d97706", label="p95")
ax.plot(df50["concurrency"], df50["p99_ms"],
        marker="^", linewidth=2, color="#dc2626", label="p99")
ax.set_xlabel("Concurrent clients", fontsize=11)
ax.set_ylabel("Latency (ms)", fontsize=11)
ax.set_title("Thread-Pool Server: Latency Percentiles vs Concurrency\n(pool size = 50)",
             fontsize=13, fontweight="bold")
ax.legend(fontsize=10)
ax.xaxis.set_major_formatter(ticker.FuncFormatter(lambda x, _: f"{int(x):,}"))
ax.fill_between(df50["concurrency"], df50["p50_ms"], df50["p99_ms"],
                alpha=0.08, color="#dc2626", label="_nolegend_")
plt.tight_layout()
out = os.path.join(OUT_DIR, "latency_percentiles.png")
plt.savefig(out)
plt.close()
print(f"Saved: {out}")
fig, ax = plt.subplots(figsize=(9, 5))

ax.plot(df50["concurrency"], df50["p99_ms"],
        marker="o", linewidth=2, color="#2563eb", label="Pool size = 50 (p99)")
ax.plot(df20["concurrency"], df20["p99_ms"],
        marker="s", linewidth=2, color="#dc2626", label="Pool size = 20 (p99)",
        linestyle="--")
ax.set_xlabel("Concurrent clients", fontsize=11)
ax.set_ylabel("p99 Latency (ms)", fontsize=11)
ax.set_title("Thread-Pool Server: p99 Tail Latency vs Concurrency\n(pool size 50 vs 20)",
             fontsize=13, fontweight="bold")
ax.legend(fontsize=10)
ax.xaxis.set_major_formatter(ticker.FuncFormatter(lambda x, _: f"{int(x):,}"))
plt.tight_layout()
out = os.path.join(OUT_DIR, "p99_comparison.png")
plt.savefig(out)
plt.close()
print(f"Saved: {out}")
print("\nAll plots written to results/plots/")
print("Pool-50 data summary:")
print(df50[["concurrency","requests_per_sec","p50_ms","p95_ms","p99_ms"]].to_string(index=False))