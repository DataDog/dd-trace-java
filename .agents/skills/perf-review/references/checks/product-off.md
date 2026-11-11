# Work done for a product that's off

Check J14.

Optional products (DSM, DBM, DJM, AppSec, IAST, LLMObs and others) are off for most customers.
Work done for them while they are off is paid by everyone.

## J14 Work done for an optional product that's off

- **Flag:** on a per-request or per-message path, work whose only consumer is an optional product
  (its tags, contexts, hashed keys, strings or queue items) that isn't behind that product's
  enabled check. This includes work added to a product's entry points that application threads
  call whether the product is on or not. With DSM off, `DefaultDataStreamsMonitoring.trackBacklog`,
  `trackTransaction(String, String)` and `mergePathwayContextIntoSpan` still run unchecked.
- **Not when:**
  - the integration is installed only when the product is on (DJM's Spark integration);
  - it is the product's own internals that only run when the product is on (DSM's background
    aggregation and flush);
  - the path is cold;
  - the check itself neither allocates nor hashes;
  - it builds one small wrapper of precomputed values (for example the gRPC server's
    `fromTags(SERVER_PATHWAY_EDGE_TAGS)`).
- **A no-op receiver does not clear it.** The arguments are built before the call, which is the
  exception to "follow hooks to their sink" in `../posture.md`. Also check the no-op in the
  implementation that actually runs: with DSM off, `DefaultDataStreamsMonitoring.trackBacklog`
  still allocates and queues a `Backlog`.
- **Confidence / level:** flag-with-confidence. P2 on per-message messaging paths, P3 elsewhere.
- **Fix:** put the product's existing check around both building the arguments and the call,
  keeping it static or dynamic as it already is. If the call is shared with tracing (for example
  `defaultPropagator().inject`), gate only the product's part: inject the span without the DSM
  context. The checks to use:
  - DSM: `traceConfig().isDataStreamsEnabled()`. It is dynamic (remote config), so never a
    `static final`;
  - DBM: the `static final` flags in `JDBCDecorator` / `MongoCommentInjector`;
  - DJM: `InstrumenterConfig.get().isDataJobsEnabled()`, usually applied at install time;
  - AppSec: `ActiveSubsystems.APPSEC_ACTIVE`;
  - otherwise, the check the product's own code uses.
- **Why:** the Kafka consumer's ungated DSM tags cost 548 B per record (−13.7%) and 6.6%
  throughput, and the same miss was in five messaging paths.
