#!/usr/bin/env bash
# Run the real TLS/TCP saturation benchmark and generate its chart.
# Usage: ./bench.sh [python_exe] [operations] [authority_threads] [in_flight_csv] [--profile]
set -e
cd "$(dirname "$0")"

PYTHON="${1:-python}"
OPERATIONS="${2:-100000}"
AUTHORITY_THREADS="${3:-8}"
IN_FLIGHT="${4:-1,8,32,128,512,2048,4096}"
PROFILE="${5:-}"

GRADLE_ARGS=(
  :core:cleanTest :core:test -Pbench
  "-PbenchOperations=$OPERATIONS"
  "-PbenchAuthorityThreads=$AUTHORITY_THREADS"
  "-PbenchInFlight=$IN_FLIGHT"
  --tests org.edtp.theexchange.concurrency.ConcurrencyBenchmark
  --rerun-tasks
)
if [[ "$PROFILE" == "--profile" ]]; then
  GRADLE_ARGS+=("-PbenchJfr=core/build/reports/bench/benchmark.jfr")
fi

echo "=== TLS/TCP Saturation Benchmark ==="
./gradlew "${GRADLE_ARGS[@]}"

echo ""
echo "=== Generating chart ==="
"$PYTHON" bench_plot.py bench_data.csv

echo ""
if [[ "$PROFILE" == "--profile" ]]; then
  echo "=== Done: bench_report.png + core/build/reports/bench/benchmark.jfr ==="
else
  echo "=== Done: bench_report.png ==="
fi
