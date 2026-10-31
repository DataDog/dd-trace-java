# MySQL statement cache benchmark

- date: 2026-10-30T21:51:02Z
- host: Darwin 25.6.0 arm64
- cpus: 16
- java: openjdk version 21.0.11 2026-04-21 LTS
- mysql: 8.0.46 (loopback)
- agentJar: <repo>/dd-java-agent/build/libs/dd-java-agent-1.68.0-SNAPSHOT.jar
- agentCommit: 73850292b7
- startupRuns: 8
- warmupS: 20
- durationS: 45
- connections: 8
- heap: 512m

## Startup to first successful request (ms)

| config | median | min | max | runs |
|---|---:|---:|---:|---:|
| noagent | 947 | 900 | 996 | 8 |
| agent | 1571 | 1545 | 1648 | 8 |
| agent-stmtcache | 1518 | 1486 | 1588 | 8 |

## Load (relative columns vs `noagent`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nodb | noagent | 111829 | - | 67.5 | 145.9 | 32.38 | - | 93 | 0 |
| nodb | agent | 98485 | -11.9% | 72.4 | 223.1 | 43.38 | +34.0% | 163 | 0 |
| nodb | agent-stmtcache | 106512 | -4.8% | 69.9 | 160.8 | 42.64 | +31.7% | 161 | 0 |
| db | noagent | 3771 | - | 2060.6 | 3236.3 | 181.23 | - | 37 | 0 |
| db | agent | 3847 | +2.0% | 2031.5 | 2961.4 | 215.24 | +18.8% | 32 | 0 |
| db | agent-stmtcache | 3895 | +3.3% | 2030.4 | 2664.8 | 199.05 | +9.8% | 31 | 0 |

## Effective Connector/J statement cache at end of each config's load phase

Read from one pooled connection. cachePrepStmts: 1 on, 0 off. cachedStatements: parsed statements in that connection's cache, -1 when there is no cache.

| config | cachePrepStmts | cachedStatements |
|---|---:|---:|
| noagent | 0 | -1 |
| agent | 0 | -1 |
| agent-stmtcache | 1 | 5 |
