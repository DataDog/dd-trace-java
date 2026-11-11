# Startup latency

P2.

"Once per process" treats startup costs as negligible — but that breaks for serverless (cold starts
are routine), short-lived CI jobs, and deployments that track startup time.

Flag in premain-reachable code: eager class loading, native library loads (`Native.load`),
reflection setup, config-regex compilation, eager file/network I/O, and thread creation. Fix:
defer to first-use off the hot path, or a background thread post-startup.

Startup latency and bootstrap correctness share a lens. The bootstrap constraints (no
`java.util.logging` / `java.nio` / `javax.management` in premain) are the correctness side;
startup latency is the performance side. Both route to the platform team — not as contributor
nudges.

*Examples:*
- `Native.load` inside a `write()` method. If reached on the startup path, it's a
  present startup-latency cost (loading libc + building the JNA proxy), not just a latent one.
- **Instrumentation static initializers** — any static field initialization in an `Instrumenter`
  subclass that triggers class loading or I/O on first reference is premain-reachable.
