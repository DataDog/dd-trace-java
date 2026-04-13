# Memory Pressure Benchmark

Measures tracer throughput overhead across decreasing heap sizes using spring-petclinic and JMeter.

Based on Doug's methodology: run petclinic with `GET /owners/3` (DB-backed endpoint) at heap sizes from 256m down to 64m, comparing no-agent baseline vs candidate agent.

## Quick start

```bash
# Full run: baseline + candidate at all heap sizes
./run.sh

# With a specific agent jar
./run.sh --agent /path/to/dd-java-agent.jar

# Quick test with fewer heap sizes and shorter durations
./run.sh --heap-sizes 256,128,64 --warmup 15 --measure 30

# With JFR profiling
./run.sh --jfr

# Only run candidate (skip baseline if you already have baseline numbers)
./run.sh --skip-baseline

# Test a specific optimization with extra agent opts
./run.sh --agent-opts "-Ddd.appsec.enabled=false -Ddd.profiling.enabled=false"
```

## What it does

For each heap size (default: 256, 192, 128, 96, 80, 64 MiB):

1. Starts petclinic with `-Xms<N>m -Xmx<N>m` (no agent)
2. Runs JMeter with 8 threads for warmup + measurement
3. Records stabilized throughput (measurement window only)
4. Repeats with `-javaagent:dd-java-agent.jar`
5. If petclinic OOMs or fails to start, records "OOM"

## Output

```
Heap      Baseline        Candidate       Delta
------    -----------     -----------     -----------
256m      22400 req/s     19500 req/s     -12.9%
192m      21750 req/s     19000 req/s     -12.6%
128m      21500 req/s     14750 req/s     -31.4%
96m       16750 req/s     13000 req/s     -22.4%
80m       16350 req/s     11250 req/s     -31.2%
64m       15000 req/s     7500 req/s      -50.0%
```

Results are also saved as CSV in `results/<timestamp>/summary.csv` with per-run JTL files and logs.

## Dependencies

Auto-downloaded on first run:
- **JMeter 5.6.3** → `tools/apache-jmeter-5.6.3/`
- **spring-petclinic 3.3.0** → `tools/spring-petclinic/` (built with Maven)
- **dd-java-agent** → auto-detected from `dd-java-agent/build/libs/` or built via Gradle

Requires: Java 17+, Python 3 (for JTL parsing), curl, git.

## Comparing versions

To compare two tracer versions, run separately and diff the CSVs:

```bash
# Run with release version
./run.sh --agent /path/to/dd-java-agent-1.61.0.jar --output ./results/v1.61.0

# Run with candidate
./run.sh --agent /path/to/dd-java-agent-SNAPSHOT.jar --output ./results/candidate

# Compare
paste -d, results/v1.61.0/summary.csv results/candidate/summary.csv
```
