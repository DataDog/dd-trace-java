# MySQL statement cache benchmark

- date: 2026-10-31T00:37:17Z
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
| agent-stmtcache | 1662 | 1578 | 1874 | 8 |
| agent | 1494 | 1448 | 1568 | 8 |
| noagent | 866 | 858 | 875 | 8 |

## Load (relative columns vs `agent-stmtcache`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nodb | agent-stmtcache | 104390 | - | 71.6 | 164.5 | 44.13 | - | 152 | 0 |
| nodb | agent | 105360 | +0.9% | 71.3 | 163.5 | 43.09 | -2.4% | 156 | 0 |
| nodb | noagent | 112747 | +8.0% | 68.4 | 133.6 | 31.76 | -28.0% | 92 | 0 |
| db | agent-stmtcache | 3925 | - | 2015.4 | 2606.1 | 197.38 | - | 31 | 0 |
| db | agent | 3921 | -0.1% | 2017.3 | 2576.2 | 214.17 | +8.5% | 32 | 0 |
| db | noagent | 4010 | +2.2% | 1975.3 | 2526.1 | 171.75 | -13.0% | 27 | 0 |

## Effective Connector/J statement cache at end of each config's load phase

Read from one pooled connection. cachePrepStmts: 1 on, 0 off. cachedStatements: parsed statements in that connection's cache, -1 when there is no cache.

| config | cachePrepStmts | cachedStatements |
|---|---:|---:|
| agent-stmtcache | 1 | 5 |
| agent | 0 | -1 |
| noagent | 0 | -1 |
