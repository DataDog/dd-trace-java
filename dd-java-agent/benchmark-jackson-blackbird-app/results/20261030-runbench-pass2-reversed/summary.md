# Jackson Blackbird benchmark

- date: 2026-10-30T21:34:16Z
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
| agent-blackbird | 1489 | 1441 | 1675 | 5 |
| agent | 1438 | 1428 | 1478 | 5 |
| noagent | 850 | 820 | 896 | 5 |

## Load (relative columns vs `agent-blackbird`)

| endpoint | config | req/s | req/s vs base | p50 us | p99 us | CPU us/req | CPU/req vs base | GC ms | classes loaded | errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nojson | agent-blackbird | 100411 | - | 73.8 | 169.9 | 45.11 | - | 155 | 61 | 0 |
| nojson | agent | 99187 | -1.2% | 74.0 | 175.3 | 45.85 | +1.7% | 155 | 1 | 0 |
| nojson | noagent | 110277 | +9.8% | 69.1 | 143.6 | 33.90 | -24.8% | 118 | 2 | 0 |
| json | agent-blackbird | 75723 | - | 98.0 | 234.8 | 64.83 | - | 141 | 0 | 0 |
| json | agent | 72183 | -4.7% | 101.4 | 247.0 | 69.96 | +7.9% | 159 | 0 | 0 |
| json | noagent | 79731 | +5.3% | 96.3 | 180.9 | 53.67 | -17.2% | 116 | 0 | 0 |
| jsonlist | agent-blackbird | 57825 | - | 130.9 | 263.6 | 84.68 | - | 102 | 2 | 0 |
| jsonlist | agent | 58145 | +0.6% | 130.3 | 262.5 | 85.41 | +0.9% | 113 | 3 | 0 |
| jsonlist | noagent | 60795 | +5.1% | 126.2 | 229.3 | 71.81 | -15.2% | 87 | 0 | 0 |
| newmapper | agent-blackbird | 25891 | - | 289.7 | 926.0 | 280.59 | - | 695 | 1 | 0 |
| newmapper | agent | 25358 | -2.1% | 300.1 | 925.3 | 290.03 | +3.4% | 673 | 0 | 0 |
| newmapper | noagent | 27799 | +7.4% | 272.1 | 854.5 | 252.13 | -10.1% | 650 | 0 | 0 |

## Blackbird status at the end of each config's load phase

1 = Blackbird registered on Spring's ObjectMapper / on a `new ObjectMapper()` created at app startup; classes = classes loaded during the last endpoint's measured window.

| config | Spring mapper | new mapper | classes loaded (last endpoint) |
|---|---:|---:|---:|
| agent-blackbird | 1 | 1 | 1 |
| agent | 0 | 0 | 0 |
| noagent | 0 | 0 | 0 |

## Response bytes vs `agent-blackbird`

| endpoint | config | bytes | identical |
|---|---|---:|---|
| nojson | agent-blackbird | 5025 | yes |
| nojson | agent | 5025 | yes |
| nojson | noagent | 5025 | yes |
| json | agent-blackbird | 4805 | yes |
| json | agent | 4805 | yes |
| json | noagent | 4805 | yes |
| jsonlist | agent-blackbird | 17730 | yes |
| jsonlist | agent | 17730 | yes |
| jsonlist | noagent | 17730 | yes |
| newmapper | agent-blackbird | 4805 | yes |
| newmapper | agent | 4805 | yes |
| newmapper | noagent | 4805 | yes |
