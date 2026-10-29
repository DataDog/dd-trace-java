# Regex cache benchmark

- date: 2026-10-29T20:25:32Z
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
| agent-cache | 1628 | 1610 | 1698 | 5 |
| agent | 1457 | 1407 | 1688 | 5 |
| noagent | 806 | 785 | 832 | 5 |

## Load (relative columns vs `agent-cache`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| noregex | agent-cache | 100890 | - | 73.3 | 171.5 | 44.69 | - | 109 | 0 |
| noregex | agent | 104501 | +3.6% | 71.4 | 162.5 | 43.48 | -2.7% | 111 | 0 |
| noregex | noagent | 112824 | +11.8% | 67.9 | 140.8 | 32.17 | -28.0% | 67 | 0 |
| split | agent-cache | 103493 | - | 71.8 | 164.5 | 44.08 | - | 106 | 0 |
| split | agent | 104859 | +1.3% | 71.2 | 161.8 | 43.42 | -1.5% | 117 | 0 |
| split | noagent | 112232 | +8.4% | 68.5 | 136.7 | 32.93 | -25.3% | 66 | 0 |
| sanitize | agent-cache | 101335 | - | 73.5 | 164.4 | 46.05 | - | 106 | 0 |
| sanitize | agent | 99568 | -1.7% | 73.9 | 175.2 | 46.12 | +0.2% | 109 | 0 |
| sanitize | noagent | 114046 | +12.5% | 66.8 | 139.0 | 33.36 | -27.6% | 70 | 0 |
| email | agent-cache | 102603 | - | 72.7 | 162.5 | 44.69 | - | 110 | 0 |
| email | agent | 100460 | -2.1% | 74.3 | 165.8 | 46.99 | +5.1% | 209 | 0 |
| email | noagent | 109902 | +7.1% | 69.4 | 144.2 | 34.94 | -21.8% | 172 | 0 |
| mask | agent-cache | 98941 | - | 74.1 | 177.2 | 44.92 | - | 104 | 0 |
| mask | agent | 104436 | +5.6% | 71.5 | 161.3 | 43.69 | -2.7% | 113 | 0 |
| mask | noagent | 110280 | +11.5% | 68.3 | 154.4 | 32.78 | -27.0% | 66 | 0 |
| compile | agent-cache | 99131 | - | 75.1 | 168.4 | 48.95 | - | 96 | 0 |
| compile | agent | 100150 | +1.0% | 74.7 | 162.4 | 48.49 | -0.9% | 102 | 0 |
| compile | noagent | 107899 | +8.8% | 70.7 | 145.8 | 36.60 | -25.2% | 62 | 0 |
| dynamic | agent-cache | 103218 | - | 72.1 | 163.9 | 44.82 | - | 116 | 0 |
| dynamic | agent | 101739 | -1.4% | 73.1 | 168.7 | 44.85 | +0.1% | 112 | 0 |
| dynamic | noagent | 112802 | +9.3% | 67.7 | 139.6 | 33.47 | -25.3% | 70 | 0 |

## RegexCache counters at end of each config's load phase

-1 means the counters were not reachable (no agent, or agent without RegexCache).

| config | hits | misses | admissions |
|---|---:|---:|---:|
| agent-cache | 64262696 | 4592423 | 5 |
| agent | 0 | 0 | 0 |
| noagent | -1 | -1 | -1 |
