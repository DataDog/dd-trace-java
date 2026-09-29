# Benchmarks

GitLab CI configuration for the benchmarks that run on the
[Benchmarking Platform](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/2419261562/Benchmarking+Platform).

## Layout

- `benchmarks.yml`: dsm-kafka and debugger benchmarks.
    - `dsm-kafka-producer-benchmark` and `dsm-kafka-consumer-benchmark` extend
      `.dsm-kafka-benchmarks`.
        - Run JMH microbenchmarks via `bp-runner`, then convert, upload and comment on the PR.
        - Steps live in the `java/kafka-dsm-overhead` branch of
          [benchmarking-platform](https://github.com/DataDog/benchmarking-platform).
    - `debugger-benchmarks`: k6 load test against Spring petclinic.
        - Runs via `bp-runner`, then converts, uploads and comments on the PR.
        - Steps live in the `java/debugger-benchmarks` branch of
          [benchmarking-platform](https://github.com/DataDog/benchmarking-platform).
- `java-benchmark-configs.yml`: `needs` and `rules` overrides for the spring-petclinic,
  insecure-bank, startup and dacapo parallel jobs included from
  [apm-sdks-benchmarks](https://gitlab.ddbuild.io/DataDog/apm-reliability/apm-sdks-benchmarks).
    - Change the jobs themselves there.

## Marking a benchmark as flaky

Add it to `FLAKY_BENCHMARKS_REGEX` in the suite's file:

- debugger: `debugger-benchmarks` in `benchmarks.yml`.

The benchmark still runs and reports, but doesn't fail the gate.

- The regex matches anywhere in the scenario name.
    - Anchor with `^...$` to target one scenario, or it also quarantines every scenario that
      contains the name.

```yaml
FLAKY_BENCHMARKS_REGEX: "^<scenario name>$"
```

Open a ticket to fix or remove it. See
[Flaky Benchmarks Monitoring](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/7223313012/Flaky+Benchmarks+Monitoring).
