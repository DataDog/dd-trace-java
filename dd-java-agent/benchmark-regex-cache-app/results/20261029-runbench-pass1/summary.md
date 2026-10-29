# Regex cache benchmark

- date: 2026-10-29T20:08:21Z
- host: Darwin 25.6.0 arm64
- cpus: 16
- java: openjdk version 21.0.11 2026-04-21 LTS
- agentJar: <repo>/dd-java-agent/build/libs/dd-java-agent-1.68.0-SNAPSHOT.jar
- agentCommit: 96d05b7385
- startupRuns: 5
- warmupS: 15
- durationS: 30
- connections: 8
- heap: 512m

## Startup to first successful request (ms)

| config | median | min | max | runs |
|---|---:|---:|---:|---:|
| noagent | 807 | 785 | 818 | 5 |
| agent | 1419 | 1410 | 1509 | 5 |
| agent-cache | 1616 | 1569 | 1662 | 5 |

## Load (relative columns vs `noagent`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| noregex | noagent | 113573 | - | 67.1 | 139.6 | 32.12 | - | 71 | 0 |
| noregex | agent | 101538 | -10.6% | 73.0 | 167.8 | 44.86 | +39.7% | 111 | 0 |
| noregex | agent-cache | 103123 | -9.2% | 72.3 | 164.7 | 43.89 | +36.7% | 110 | 0 |
| split | noagent | 112787 | - | 67.9 | 138.8 | 32.75 | - | 69 | 0 |
| split | agent | 101844 | -9.7% | 73.2 | 164.9 | 45.19 | +38.0% | 109 | 0 |
| split | agent-cache | 104152 | -7.7% | 71.9 | 161.2 | 43.73 | +33.5% | 105 | 0 |
| sanitize | noagent | 108360 | - | 70.3 | 148.8 | 34.50 | - | 70 | 0 |
| sanitize | agent | 100238 | -7.5% | 74.3 | 166.6 | 46.95 | +36.1% | 109 | 0 |
| sanitize | agent-cache | 102414 | -5.5% | 72.8 | 164.3 | 45.42 | +31.6% | 105 | 0 |
| email | noagent | 108710 | - | 70.3 | 145.8 | 35.27 | - | 172 | 0 |
| email | agent | 100018 | -8.0% | 74.3 | 167.6 | 47.66 | +35.1% | 213 | 0 |
| email | agent-cache | 105092 | -3.3% | 70.9 | 159.5 | 43.58 | +23.6% | 112 | 0 |
| mask | noagent | 113424 | - | 67.6 | 136.9 | 32.27 | - | 70 | 0 |
| mask | agent | 102037 | -10.0% | 72.5 | 166.7 | 44.90 | +39.2% | 110 | 0 |
| mask | agent-cache | 103408 | -8.8% | 72.4 | 162.0 | 43.80 | +35.7% | 103 | 0 |
| compile | noagent | 108792 | - | 70.2 | 144.3 | 36.36 | - | 64 | 0 |
| compile | agent | 95936 | -11.8% | 76.5 | 182.8 | 50.17 | +38.0% | 100 | 0 |
| compile | agent-cache | 98442 | -9.5% | 75.8 | 170.6 | 48.95 | +34.6% | 95 | 0 |
| dynamic | noagent | 110714 | - | 68.9 | 144.1 | 33.67 | - | 73 | 0 |
| dynamic | agent | 103533 | -6.5% | 71.8 | 162.3 | 44.99 | +33.6% | 110 | 0 |
| dynamic | agent-cache | 103044 | -6.9% | 71.9 | 167.6 | 44.39 | +31.8% | 119 | 0 |

## RegexCache counters at end of each config's load phase

-1 means the counters were not reachable (no agent, or agent without RegexCache).

| config | hits | misses | admissions |
|---|---:|---:|---:|
| noagent | -1 | -1 | -1 |
| agent | 0 | 0 | 0 |
| agent-cache | 65368822 | 4634160 | 6 |
