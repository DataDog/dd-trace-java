# agent-iast

## Benchmark results

### String

#### concat()

| Benchmark                           | Mode | Cnt   | Score   | Error   | Units |
|-------------------------------------|------|-------|---------|---------|-------|
| StringConcatBenchmark.baseline      | ss   | 15000 |  50.977 | ± 1.561 | ns/op |
| StringConcatBenchmark.iastDisabled  | ss   | 15000 |  52.963 | ± 0.748 | ns/op |
| StringConcatBenchmark.notTainted    | ss   | 15000 |  77.463 | ± 0.990 | ns/op |
| StringConcatBenchmark.stringTainted | ss   | 15000 | 132.859 | ± 3.565 | ns/op |
| StringConcatBenchmark.paramTainted  | ss   | 15000 | 156.825 | ± 4.530 | ns/op |
| StringConcatBenchmark.bothTainted   | ss   | 15000 | 145.427 | ± 3.138 | ns/op |

### String Builder

#### constructor()

| Benchmark                               | Mode | Cnt   | Score   | Error   | Units |
|-----------------------------------------|------|-------|---------|---------|-------|
| StringBuilderInitBenchmark.baseline     | ss   | 15000 | 43.278  | ± 0.666 | ns/op |
| StringBuilderInitBenchmark.iastDisabled | ss   | 15000 | 45.373  | ± 2.391 | ns/op |
| StringBuilderInitBenchmark.notTainted   | ss   | 15000 | 66.833  | ± 1.292 | ns/op |
| StringBuilderInitBenchmark.tainted      | ss   | 15000 | 100.316 | ± 2.767 | ns/op |

#### append()

| Benchmark                                         | Mode | Cnt   | Score   | Error   | Units |
|---------------------------------------------------|------|-------|---------|---------|-------|
| StringBuilderAppendBenchmark.baseline             | ss   | 15000 |  50.261 | ± 2.212 | ns/op |
| StringBuilderAppendBenchmark.iastDisabled         | ss   | 15000 |  52.746 | ± 0.567 | ns/op |
| StringBuilderAppendBenchmark.notTainted           | ss   | 15000 |  90.821 | ± 2.245 | ns/op |
| StringBuilderAppendBenchmark.stringBuilderTainted | ss   | 15000 |  79.958 | ± 2.289 | ns/op |
| StringBuilderAppendBenchmark.paramTainted         | ss   | 15000 | 116.093 | ± 3.961 | ns/op |
| StringBuilderAppendBenchmark.bothTainted          | ss   | 15000 | 107.229 | ± 4.275 | ns/op |

#### toString()

| Benchmark                                   | Mode | Cnt   | Score  | Error   | Units |
|---------------------------------------------|------|-------|--------|---------|-------|
| StringBuilderToStringBenchmark.baseline     | ss   | 15000 | 29.817 | ± 2.493 | ns/op |
| StringBuilderToStringBenchmark.iastDisabled | ss   | 15000 | 30.570 | ± 1.794 | ns/op |
| StringBuilderToStringBenchmark.notTainted   | ss   | 15000 | 57.370 | ± 1.333 | ns/op |
| StringBuilderToStringBenchmark.tainted      | ss   | 15000 | 92.077 | ± 1.775 | ns/op |

### batch append operations

| Benchmark                                 | (stringCount) | (taintedPct) | Mode | Cnt   | Score | Error | Units |
|-------------------------------------------|---------------|--------------|------|-------|-------|-------|-------|
| StringBuilderBatchBenchmark.baseline      | 10            | 0            | ss   | 15000 | 0.348 | 0.009 | us/op |
| StringBuilderBatchBenchmark.baseline      | 10            | 50           | ss   | 15000 | 0.317 | 0.009 | us/op |
| StringBuilderBatchBenchmark.baseline      | 10            | 100          | ss   | 15000 | 0.355 | 0.010 | us/op |
| StringBuilderBatchBenchmark.iastDisabled  | 10            | 0            | ss   | 15000 | 0.355 | 0.009 | us/op |
| StringBuilderBatchBenchmark.iastDisabled  | 10            | 50           | ss   | 15000 | 0.344 | 0.008 | us/op |
| StringBuilderBatchBenchmark.iastDisabled  | 10            | 100          | ss   | 15000 | 0.370 | 0.013 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 10            | 0            | ss   | 15000 | 0.551 | 0.014 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 10            | 50           | ss   | 15000 | 0.794 | 0.014 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 10            | 100          | ss   | 15000 | 0.900 | 0.014 | us/op |
| StringBuilderBatchBenchmark.baseline      | 100           | 0            | ss   | 15000 | 2.508 | 0.025 | us/op |                                                                
| StringBuilderBatchBenchmark.baseline      | 100           | 50           | ss   | 15000 | 2.419 | 0.019 | us/op |                                                                
| StringBuilderBatchBenchmark.baseline      | 100           | 100          | ss   | 15000 | 2.499 | 0.026 | us/op |  
| StringBuilderBatchBenchmark.iastDisabled  | 100           | 0            | ss   | 15000 | 2.499 | 0.023 | us/op |
| StringBuilderBatchBenchmark.iastDisabled  | 100           | 50           | ss   | 15000 | 2.608 | 0.180 | us/op |
| StringBuilderBatchBenchmark.iastDisabled  | 100           | 100          | ss   | 15000 | 2.596 | 0.201 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 100           | 0            | ss   | 15000 | 3.426 | 0.028 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 100           | 50           | ss   | 15000 | 6.676 | 0.275 | us/op |
| StringBuilderBatchBenchmark.iastEnabled   | 100           | 100          | ss   | 15000 | 7.539 | 0.164 | us/op |

### StringConcatFactory

#### makeConcatWithConstants()

| Benchmark                                  | Mode | Cnt   | Score   | Error   | Units |
|--------------------------------------------|------|-------|---------|---------|-------|
| StringConcatFactoryBenchmark.baseline      | ss   | 15000 | 43.840  | ± 2.497 | ns/op |
| StringConcatFactoryBenchmark.iastDisabled  | ss   | 15000 | 49.183  | ± 1.504 | ns/op |
| StringConcatFactoryBenchmark.notTainted    | ss   | 15000 | 81.634  | ± 3.864 | ns/op |
| StringConcatFactoryBenchmark.firstTainted  | ss   | 15000 | 235.579 | ± 6.860 | ns/op |
| StringConcatFactoryBenchmark.secondTainted | ss   | 15000 | 212.314 | ± 8.576 | ns/op |
| StringConcatFactoryBenchmark.bothTainted   | ss   | 15000 | 272.908 | ± 8.209 | ns/op |

| Benchmark                                      | (stringCount) | (taintedPct) | Mode | Cnt   | Score | Error   | Units |
|------------------------------------------------|---------------|--------------|------|-------|-------|---------|-------|
| StringConcatFactoryBatchBenchmark.baseline     |  10           |   0          | ss   | 15000 | 0.501 | ± 0.050 | us/op |
| StringConcatFactoryBatchBenchmark.baseline     |  10           |  50          | ss   | 15000 | 0.442 | ± 0.008 | us/op |
| StringConcatFactoryBatchBenchmark.baseline     |  10           | 100          | ss   | 15000 | 0.548 | ± 0.012 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled |  10           |   0          | ss   | 15000 | 0.558 | ± 0.012 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled |  10           |  50          | ss   | 15000 | 0.526 | ± 0.011 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled |  10           | 100          | ss   | 15000 | 0.553 | ± 0.011 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  |  10           |   0          | ss   | 15000 | 0.580 | ± 0.011 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  |  10           |  50          | ss   | 15000 | 0.940 | ± 0.015 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  |  10           | 100          | ss   | 15000 | 0.979 | ± 0.013 | us/op |
| StringConcatFactoryBatchBenchmark.baseline     | 100           |   0          | ss   | 15000 | 3.420 | ± 0.033 | us/op |
| StringConcatFactoryBatchBenchmark.baseline     | 100           |  50          | ss   | 15000 | 3.538 | ± 0.038 | us/op |
| StringConcatFactoryBatchBenchmark.baseline     | 100           | 100          | ss   | 15000 | 3.416 | ± 0.035 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled | 100           |   0          | ss   | 15000 | 3.453 | ± 0.033 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled | 100           |  50          | ss   | 15000 | 3.393 | ± 0.032 | us/op |
| StringConcatFactoryBatchBenchmark.iastDisabled | 100           | 100          | ss   | 15000 | 3.460 | ± 0.034 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  | 100           |   0          | ss   | 15000 | 4.096 | ± 0.167 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  | 100           |  50          | ss   | 15000 | 6.170 | ± 0.062 | us/op |
| StringConcatFactoryBatchBenchmark.iastEnabled  | 100           | 100          | ss   | 15000 | 7.436 | ± 0.069 | us/op |
