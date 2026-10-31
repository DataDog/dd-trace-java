# Connector/J statement cache microbenchmark (no agent)

- java: 21.0.11 OpenJDK 64-Bit Server VM
- os: Mac OS X aarch64
- driver: mysql-connector-j-8.4.0 (Revision: 1c3f5c149e0bfe31c7fbeb24e2d260cd890972c4)
- server: MySQL 8.0.46
- rounds: 8 x 5.0s per cell, median shown
- url: jdbc:mysql://127.0.0.1:13306/bench

| workload | query | off ops/s | on ops/s | off CPU us/op | on CPU us/op | CPU saved/op | CPU delta | off wall us/op | on wall us/op | off B/op | on B/op |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| prepare-only | simple | 1013620 | 3067158 | 0.98 | 0.32 | 0.66 | -66.9% | 0.99 | 0.33 | 1400 | 792 |
| prepare-only | orm | 256651 | 2191960 | 3.88 | 0.45 | 3.42 | -88.4% | 3.90 | 0.46 | 2968 | 1304 |
| prepare-exec | simple | 4244 | 4312 | 15.43 | 14.18 | 1.25 | -8.1% | 235.87 | 231.94 | 5911 | 5303 |
| prepare-exec | orm | 3768 | 3724 | 20.80 | 17.48 | 3.32 | -16.0% | 265.43 | 268.51 | 13524 | 11860 |
