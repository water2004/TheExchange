# Run the real TLS/TCP saturation benchmark and generate its chart.
# Usage: .\bench.ps1 [-Python <path>] [-Operations <count>]
#                    [-AuthorityThreads <count>] [-InFlight <csv>] [-Profile]
param(
    [string]$Python = "python",
    [ValidateRange(1, 10000000)]
    [int]$Operations = 100000,
    [ValidateRange(1, 1024)]
    [int]$AuthorityThreads = 8,
    [string]$InFlight = "1,8,32,128,512,2048,4096",
    [switch]$Profile
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$gradleArgs = @(
    ":core:cleanTest",
    ":core:test",
    "-Pbench",
    "-PbenchOperations=$Operations",
    "-PbenchAuthorityThreads=$AuthorityThreads",
    "-PbenchInFlight=$InFlight",
    "--tests", "org.edtp.theexchange.concurrency.ConcurrencyBenchmark",
    "--rerun-tasks"
)
if ($Profile) {
    $gradleArgs += "-PbenchJfr=core/build/reports/bench/benchmark.jfr"
}

Write-Host "=== TLS/TCP Saturation Benchmark ==="
& ./gradlew @gradleArgs

Write-Host ""
Write-Host "=== Generating chart ==="
& $Python bench_plot.py bench_data.csv

Write-Host ""
if ($Profile) {
    Write-Host "=== Done: bench_report.png + core/build/reports/bench/benchmark.jfr ==="
} else {
    Write-Host "=== Done: bench_report.png ==="
}
