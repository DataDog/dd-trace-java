# Startup latency

## Startup cost in premain-reachable code

- **Flag:** in code reachable from premain or from an instrumentation's static initializer: eager
  class loading, native library loads (`Native.load`), reflection setup, regex compilation for
  config, eager file or network I/O, and thread creation.
- **Not when:** the work happens lazily on first use, off the startup path, or on a background
  thread after startup.
- **Confidence / level:** flag-with-confidence. P2.
- **Fix:** defer it to first use off the hot path, or to a background thread after startup.
- **Why:** "once per process" is not negligible for serverless (cold starts are routine),
  short-lived CI jobs, or deployments that track startup time.
- **Examples:**
  - `Native.load` inside a `write()` method. If that is reached during startup, loading libc and
    building the JNA proxy is a startup cost today, not a latent one.
  - a static field in an `Instrumenter` subclass whose initializer loads classes or does I/O on
    first reference. That code is reachable from premain.

Startup latency is the performance side of the bootstrap constraints (no `java.util.logging`,
`java.nio` or `javax.management` in premain), which are the correctness side. Findings on both
go to the platform team, not to the contributor as nudges.
