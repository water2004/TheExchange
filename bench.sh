#!/usr/bin/env bash
# One-shot: run concurrency benchmark and generate report chart.
# Usage: ./bench.sh [python_exe]
#   python_exe  path to Python 3 with matplotlib (default: python)
set -e
cd "$(dirname "$0")"

PYTHON="${1:-python}"

echo "=== Concurrency Benchmark ==="
./gradlew :core:cleanTest :core:test -Pbench --tests "org.edtp.theexchange.concurrency.ConcurrencyBenchmark" --rerun-tasks

echo ""
echo "=== Generating chart ==="
"$PYTHON" bench_plot.py bench_data.csv

echo ""
echo "=== Done: bench_report.png ==="
