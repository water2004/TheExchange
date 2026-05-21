# One-shot: run concurrency benchmark and generate report chart.
# Usage: .\bench.ps1 [-Python <path>]
#   -Python  path to Python 3 with matplotlib (default: python)
param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "=== Concurrency Benchmark ==="
./gradlew :core:cleanTest :core:test -Pbench --tests "org.edtp.theexchange.concurrency.ConcurrencyBenchmark" --rerun-tasks

Write-Host ""
Write-Host "=== Generating chart ==="
& $Python bench_plot.py bench_data.csv

Write-Host ""
Write-Host "=== Done: bench_report.png ==="
