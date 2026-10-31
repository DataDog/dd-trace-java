# JCStress concurrency probes

The `dd-trace-core` module has a dedicated `jcstress` source set and task for
Tier-1 Java Memory Model probes:

```shell
./gradlew :dd-trace-core:jcstress
```

The task uses JCStress 0.16 and currently exercises two `ScopeContinuation`
races:

- `release` against `resume` plus scope close;
- two concurrent `resume` plus scope close operations against `release`.

Both probes require exactly one call to
`AgentTraceCollector.removeContinuation`. Zero is a continuation leak and a
value greater than one is double finalization.

The initial local run covered 156 JVM/compiler configurations. All passed with
no forbidden outcomes. This is positive evidence for the continuation counter
protocol, not proof for unrelated scope-manager behavior.
