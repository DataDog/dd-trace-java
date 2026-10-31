# Connector/J statement cache microbenchmark (no agent)

- java: 21.0.11 OpenJDK 64-Bit Server VM
- os: Mac OS X aarch64
- driver: mysql-connector-j-8.4.0 (Revision: 1c3f5c149e0bfe31c7fbeb24e2d260cd890972c4)
- server: MySQL 8.0.46
- rounds: 8 x 5.0s per cell, median shown
- url: jdbc:mysql://127.0.0.1:13306/bench

| workload | query | off ops/s | on ops/s | off CPU us/op | on CPU us/op | CPU saved/op | CPU delta | off wall us/op | on wall us/op | off B/op | on B/op |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| prepare-only | simple | 1024993 | 3289023 | 0.97 | 0.30 | 0.67 | -68.9% | 0.98 | 0.30 | 1400 | 792 |
| prepare-only | orm | 259280 | 2232551 | 3.85 | 0.44 | 3.41 | -88.5% | 3.86 | 0.45 | 2968 | 1304 |
| prepare-exec | simple | 4338 | 4411 | 14.29 | 13.20 | 1.10 | -7.7% | 230.52 | 226.69 | 5895 | 5287 |
| prepare-exec | orm | 4000 | 4161 | 20.04 | 15.38 | 4.66 | -23.2% | 250.10 | 240.31 | 13508 | 11844 |
