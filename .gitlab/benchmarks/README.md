# Debugger benchmarks

The `debugger-benchmarks` job measures the runtime overhead of dynamic
instrumentation (debugger probes) by running a k6 load test against the Spring
petclinic demo app, once with the candidate agent and once with the baseline,
and posting a comparison as a PR comment.

Previously this suite lived in the `java/debugger-benchmarks` branch of
[benchmarking-platform](https://github.com/DataDog/benchmarking-platform); it
now runs fully in-repo. Files in this directory:

- **`bp-runner.yml`** — [bp-runner](https://github.com/DataDog/benchmarking-platform-tools/tree/main/bp-runner)
  experiment definition. Fetches the baseline and candidate agents, starts the
  datadog-agent and petclinic services, runs the k6 steps, and uploads results
  to S3 and the Benchmarking Platform API. Paths are resolved from the repo
  root, which is the job's working directory.
- **`steps/`** — run, report, and PR-comment scripts called by the job and by
  `bp-runner.yml`.
  - `setup-dd-trace-java.sh` fetches the candidate agent (built by the current
    pipeline) and the baseline (master or the latest release, depending on the
    branch).
  - `start-petclinic.sh` starts petclinic with the agent and `probes.json`.
  - `start-k6.sh` runs the `script.js` k6 scenarios and converts the output.
  - `create-report.sh` / `post-pr-comment.sh` build the comparison report and
    post it on the PR.
- **`container/`** — Dockerfile and install scripts for the CI image, built via
  the manual `build-benchmark-ci-images` job (see below).
- **`.env.example`** — template for local-run environment variables (copy to
  `.env`).

The job itself (`debugger-benchmarks`) is defined in `../benchmarks.yml`. It
runs on changes under `dd-java-agent/agent-debugger/**`, or manually.

## How to add or update a benchmark

- k6 scenarios live in `steps/script.js` (one scenario per debugger
  configuration to measure). To add one, define the scenario in `script.js` and
  the corresponding probes in `steps/probes.json`.
- Service and load-generator settings (CPU pinning, heap, k6 options) live in
  `bp-runner.yml` and `steps/start-petclinic.sh` / `steps/start-k6.sh`.
- New system packages for the CI image go in `container/Dockerfile`; rebuild
  and repin the image afterwards (see below).

## How to mark a benchmark as flaky

Add it to `FLAKY_BENCHMARKS_REGEX` in the `debugger-benchmarks` job in
`../benchmarks.yml`. The benchmark still runs and reports, but doesn't fail
performance quality gates.

- The regex matches anywhere in the scenario name.
    - `noprobe` quarantines every scenario containing `noprobe`.
    - Anchor with `^...$` to target one scenario.

Open a ticket to fix or remove it. See
[Flaky Benchmarks Monitoring](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/7223313012/Flaky+Benchmarks+Monitoring).

## How to rebuild the CI image

The `DEBUGGER_BENCHMARKS_CI_IMAGE` used by the `debugger-benchmarks` job is
built from `container/` by the manual `build-benchmark-ci-images` job, defined
by
[`build-ci-images.template.yml`](https://github.com/DataDog/benchmarking-platform-tools/blob/main/images/templates/gitlab/build-ci-images.template.yml)
and included from `../benchmarks.yml`. Rebuild and repin whenever you change
anything in `container/`.

1. Push your branch and open the pipeline on
   [gitlab.ddbuild.io/DataDog/apm-reliability/dd-trace-java](https://gitlab.ddbuild.io/DataDog/apm-reliability/dd-trace-java).
2. Play the `build-benchmark-ci-images` job and wait for it to finish. It
   pushes `registry.ddbuild.io/ci/benchmarking-platform:dd-trace-java-<PIPELINE_ID>`.
3. Repin `DEBUGGER_BENCHMARKS_CI_IMAGE` in `../benchmarks.yml` to that tag
   and push. Verify a green `debugger-benchmarks` run before merging.

## How to run the suite locally

Install [bp-runner](https://github.com/DataDog/benchmarking-platform-tools/blob/main/bp-runner/INSTALL.md),
then from this directory copy `.env.example` to `.env` and set `DD_API_KEY`
(generate one on <https://ddstaging.datadoghq.com/>).

```bash
cd .gitlab/benchmarks
cp .env.example .env  # then edit DD_API_KEY
bp-runner bp-runner.yml --debug --local
```

Without `UPSTREAM_BRANCH` set, `steps/setup-dd-trace-java.sh` uses the latest
released agent for both baseline and candidate; to compare a local build, put
it at `tracers/dd-java-agent-candidate.jar` first (the scripts skip existing
downloads). See the
[troubleshooting guide](https://datadoghq.atlassian.net/wiki/spaces/APMINT/pages/3832579225/How+to+run+macrobenchmarks+locally)
for SSH errors during the local image build.

## Other benchmark suites

- `dsm-kafka-producer-benchmark` and `dsm-kafka-consumer-benchmark`
  (`../benchmarks.yml`) still clone the `java/kafka-dsm-overhead` branch of
  benchmarking-platform. They move to Speedlab separately; mark them flaky via
  `FLAKY_BENCHMARKS_REGEX` in `.dsm-kafka-benchmarks`.
- `../java-benchmark-configs.yml` holds `needs` and `rules` overrides for the
  spring-petclinic, insecure-bank, startup and dacapo parallel jobs included
  from [apm-sdks-benchmarks](https://github.com/DataDog/apm-sdks-benchmarks/tree/main/.gitlab).
  Change the jobs themselves there.
