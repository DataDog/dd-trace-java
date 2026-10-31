# JCStress concurrency probes

The `dd-trace-core` module has a dedicated `jcstress` source set and task for
Tier-1 Java Memory Model probes:

```shell
./gradlew :dd-trace-core:jcstress
```

The task uses JCStress 0.16 and currently exercises these races:

- `release` against `resume` plus scope close;
- two concurrent `resume` plus scope close operations against `release`;
- two concurrent pending-trace flushes, one of which owns an accepted element.

The continuation probes require exactly one call to
`AgentTraceCollector.removeContinuation`. Zero is a continuation leak and a
value greater than one is double finalization.

The initial continuation-only run covered 156 JVM/compiler configurations. All
passed with no forbidden outcomes. This is positive evidence for the
continuation counter protocol, not proof for unrelated scope-manager behavior.

The pending-trace probe found a forbidden outcome in 6 of 913 samples across
five JVM/compiler configurations. One actor enqueues an element and invokes
`flush()` while another actor invokes `flush()` concurrently. The first
processed marker increments the shared `flushCounter`; both callers can observe
that increment and return even though the second caller's accepted element has
not been written. The observed result was:

```text
writes observed when owning flush returned = 0
```

This is a distinct concurrent-barrier failure: each caller waits for any newer
counter value rather than an acknowledgement tied to its own marker.
