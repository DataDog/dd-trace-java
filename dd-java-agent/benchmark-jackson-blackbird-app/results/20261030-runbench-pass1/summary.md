# Jackson Blackbird benchmark

- date: 2026-10-30T21:23:54Z
- host: Darwin 25.6.0 arm64
- cpus: 16
- java: openjdk version 21.0.11 2026-04-21 LTS
- agentJar: <repo>/dd-java-agent/build/libs/dd-java-agent-1.68.0-SNAPSHOT.jar
- agentCommit: 6ec66f0b77
- startupRuns: 5
- warmupS: 15
- durationS: 30
- connections: 8
- heap: 512m

## Startup to first successful request (ms)

| config | median | min | max | runs |
|---|---:|---:|---:|---:|
| noagent | 876 | 857 | 951 | 5 |
| agent | 1614 | 1499 | 1699 | 5 |
| agent-blackbird | 1481 | 1458 | 1557 | 5 |

## Load (relative columns vs `noagent`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | classes loaded | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nojson | noagent | 107631 | - | 69.5 | 155.3 | 34.17 | - | 115 | 2 | 0 |
| nojson | agent | 96123 | -10.7% | 74.5 | 195.0 | 46.29 | +35.5% | 160 | 60 | 0 |
| nojson | agent-blackbird | 96305 | -10.5% | 74.6 | 185.7 | 46.83 | +37.1% | 171 | 61 | 0 |
| json | noagent | 79080 | - | 95.5 | 197.9 | 54.34 | - | 119 | 0 | 0 |
| json | agent | 64149 | -18.9% | 110.6 | 297.4 | 77.04 | +41.8% | 167 | 0 | 0 |
| json | agent-blackbird | 72531 | -8.3% | 100.9 | 243.5 | 66.84 | +23.0% | 147 | 0 | 0 |
| jsonlist | noagent | 49031 | - | 131.3 | 586.8 | 77.76 | - | 88 | 0 | 0 |
| jsonlist | agent | 55372 | +12.9% | 135.3 | 282.8 | 89.09 | +14.6% | 124 | 1 | 0 |
| jsonlist | agent-blackbird | 57556 | +17.4% | 131.2 | 269.9 | 85.40 | +9.8% | 101 | 2 | 0 |
| newmapper | noagent | 20262 | - | 331.3 | 1194.9 | 300.60 | - | 656 | 0 | 0 |
| newmapper | agent | 24022 | +18.6% | 313.1 | 967.2 | 303.76 | +1.1% | 671 | 0 | 0 |
| newmapper | agent-blackbird | 24931 | +23.0% | 305.0 | 935.0 | 295.74 | -1.6% | 677 | 1 | 0 |

## Blackbird status at the end of each config's load phase

1 = Blackbird registered on Spring's ObjectMapper / on a `new ObjectMapper()` created at app startup; classes = classes loaded during the last endpoint's measured window.

| config | Spring mapper | new mapper | classes loaded (last endpoint) |
|---|---:|---:|---:|
| noagent | 0 | 0 | 0 |
| agent | 0 | 0 | 0 |
| agent-blackbird | 1 | 1 | 1 |

## Response bytes vs `noagent`

| endpoint | config | bytes | identical |
|---|---|---:|---|
| nojson | noagent | 5025 | yes |
| nojson | agent | 5025 | yes |
| nojson | agent-blackbird | 5025 | yes |
| json | noagent | 4805 | yes |
| json | agent | 4805 | yes |
| json | agent-blackbird | 4805 | yes |
| jsonlist | noagent | 17730 | yes |
| jsonlist | agent | 17730 | yes |
| jsonlist | agent-blackbird | 17730 | yes |
| newmapper | noagent | 4805 | yes |
| newmapper | agent | 4805 | yes |
| newmapper | agent-blackbird | 4805 | yes |
