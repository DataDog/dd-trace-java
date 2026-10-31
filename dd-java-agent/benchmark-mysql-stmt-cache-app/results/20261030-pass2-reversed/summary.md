# MySQL statement cache benchmark

- date: 2026-10-30T22:07:20Z
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
| agent-stmtcache | 1507 | 1481 | 1542 | 8 |
| agent | 1548 | 1516 | 1631 | 8 |
| noagent | 884 | 873 | 908 | 8 |

## Load (relative columns vs `agent-stmtcache`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nodb | agent-stmtcache | 104481 | - | 71.7 | 166.8 | 43.61 | - | 153 | 0 |
| nodb | agent | 106540 | +2.0% | 70.0 | 162.7 | 42.69 | -2.1% | 158 | 0 |
| nodb | noagent | 54054 | -48.3% | 137.5 | 313.9 | 61.27 | +40.5% | 128 | 0 |
| db | agent-stmtcache | 4006 | - | 1965.8 | 2610.4 | 192.73 | - | 31 | 0 |
| db | agent | 2040 | -49.1% | 3873.5 | 9987.2 | 381.57 | +98.0% | 40 | 0 |
| db | noagent | 2055 | -48.7% | 3647.5 | 7101.8 | 323.27 | +67.7% | 25 | 0 |

## Effective Connector/J statement cache at end of each config's load phase

Read from one pooled connection. cachePrepStmts: 1 on, 0 off. cachedStatements: parsed statements in that connection's cache, -1 when there is no cache.

| config | cachePrepStmts | cachedStatements |
|---|---:|---:|
| agent-stmtcache | 1 | 5 |
| agent | 0 | -1 |
| noagent | 0 | -1 |
