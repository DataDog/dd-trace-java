# Work done for a product that's off

Check J14.

- **J14 — Work done for an optional product that's off** *(refines #1, #2; APMLP-2124)*. Optional
  products (DSM, DBM, DJM, AppSec, IAST, LLMObs, …) are off for most customers, so work done for
  them while off is paid by everyone. Flag, on a per-request / per-message path, work whose only
  consumer is an optional product (its tags, contexts, hashed keys, strings, queue items) that
  isn't behind that product's enabled check. Measured: the Kafka consumer's ungated DSM tags cost
  548 B per record (−13.7%) and 6.6% throughput, and the same miss was in five messaging paths.
  - **A no-op receiver doesn't clear it:** the arguments are built before the call (the exception
    to "resolve-via-sink" in `../posture.md`). And verify the no-op in the implementation that
    actually runs: with DSM off, `DefaultDataStreamsMonitoring.trackBacklog` still allocates and
    queues a `Backlog`.
  - **Fix: put the product's existing check around both the construction and the call**, keeping
    it static or dynamic as it is. If the call is shared with tracing
    (`defaultPropagator().inject`), gate only the product's part: inject the span without the DSM
    context. DSM: `traceConfig().isDataStreamsEnabled()`, dynamic (remote config), so never a
    `static final`. DBM: the `static final` flags in `JDBCDecorator` / `MongoCommentInjector`. DJM:
    `InstrumenterConfig.get().isDataJobsEnabled()`, usually applied at install time. AppSec:
    `ActiveSubsystems.APPSEC_ACTIVE`. Otherwise, use the check the product's own code uses.
  - **Don't flag:** integrations installed only when the product is on (DJM's Spark); the
    product's own internals that only run when it's on (e.g. DSM's background aggregation and
    flush); cold paths; checks that neither allocate nor hash; a single small wrapper of
    precomputed values (the gRPC server's `fromTags(SERVER_PATHWAY_EDGE_TAGS)`).
  - **Do flag** work added to a product's entry points that app threads call whether or not it's
    on. With DSM off, `DefaultDataStreamsMonitoring.trackBacklog`,
    `trackTransaction(String, String)` and `mergePathwayContextIntoSpan` still run unchecked.
  - **flag-with-confidence** — P2 on messaging per-message paths, P3 elsewhere.
