/**
 * Client-side stats aggregate eligible completed spans by resource, service, operation, span kind,
 * status, and configured tags. Each reporting interval produces hit and error counts and latency
 * distributions. The aggregates are computed independently of trace sampling, so they include spans
 * that are not sent individually.
 *
 * <p>A {@link datadog.trace.common.metrics.MetricWriter} publishes each interval: msgpack to the
 * Datadog Agent or OTLP to the configured OTLP metrics endpoint.
 *
 * <h2>At a glance</h2>
 *
 * <pre>{@code
 * App / producer threads                        Single aggregator thread
 * ----------------------                        ------------------------
 *
 * span finishes                                 (1) drain inbox
 *      |                                              |
 *      v                     MPSC inbox               v
 * build immutable       (lock-free hand-off)     (2) canonicalize each label through its
 * SpanSnapshot        ----------------------->       cardinality handler (Core / PeerTag /
 *      |                                              AdditionalTags); overflow -> sentinel
 *      v                                              |
 * (no locks, no                                       v
 *  mutable state)                               (3) fold into AggregateTable, keyed by the
 *                                                    canonical labels (counts + histograms)
 *                                                     |
 *                                                     v  every ~10s (reporting interval)
 *                                                (4) flush via MetricWriter -- msgpack to the
 *                                                    agent, OTLP to the OTLP endpoint -- then
 *                                                    reset entries for the next interval
 * }</pre>
 *
 * <h2>Threading model (the load-bearing rule)</h2>
 *
 * Producer application threads do almost nothing: each captures an immutable {@link
 * datadog.trace.common.metrics.SpanSnapshot} of the finished span and hands it off through a
 * lock-free MPSC {@code inbox}. A <em>single</em> aggregator thread ({@link
 * datadog.trace.common.metrics.Aggregator}) drains that inbox and owns <em>all</em> mutable state
 * -- the {@link datadog.trace.common.metrics.AggregateTable} and every cardinality handler -- as
 * its sole writer. None of that state is synchronized; correctness rests entirely on the
 * single-writer invariant. Any cross-thread request that must mutate (e.g. clearing the table on
 * downgrade) does not touch the state directly -- it is funneled onto the aggregator thread as a
 * signal through the same inbox. If you add a mutation path, it goes through the inbox too.
 *
 * <h2>The cycle</h2>
 *
 * The aggregator folds each drained snapshot into an {@link
 * datadog.trace.common.metrics.AggregateEntry}, keyed by the canonical form of its labels, updating
 * that entry's counters and histograms. Once per reporting interval (~10s) it flushes the whole
 * table through a {@link datadog.trace.common.metrics.MetricWriter} -- msgpack ({@link
 * datadog.trace.common.metrics.SerializingMetricWriter}) or OTLP -- then resets each entry's
 * counters for the next interval rather than discarding the table: entries (and their cross-cycle
 * caches) are retained for reuse, and stale ones are expunged over subsequent cycles. Buckets are
 * per-interval deltas, not cumulative.
 *
 * <h2>Cardinality is the thing that must stay bounded</h2>
 *
 * Grouping keys come from span/user data, so distinct values -- and therefore the series count, its
 * memory, and its backend cost -- would grow without bound if left alone. High-cardinality labels
 * are canonicalized through a cardinality handler -- {@link
 * datadog.trace.common.metrics.PropertyCardinalityHandler} for the core string fields (resource,
 * service, operation, ...) and {@link datadog.trace.common.metrics.TagCardinalityHandler} for peer
 * and additional tags -- that caps the number of distinct values per label and collapses any
 * overflow into a shared sentinel, so over-cardinality folds into one entry rather than exploding
 * the table. (Primitive key fields such as HTTP/gRPC status and the boolean flags are inherently
 * low-cardinality and copied directly.) The collapses are counted per cycle and reported (to {@link
 * datadog.trace.core.monitor.HealthMetrics} and, rate-limited, through {@link
 * datadog.trace.common.metrics.CardinalityLimitReporter}) -- so the bounding is observable without
 * the bookkeeping itself becoming unbounded.
 *
 * <h2>Three handler families</h2>
 *
 * <ul>
 *   <li>{@link datadog.trace.common.metrics.CoreHandlers} -- the always-present per-field handlers
 *       (resource, service, operation, ...).
 *   <li>{@link datadog.trace.common.metrics.PeerTagSchema} -- remote-config / feature-discovery
 *       driven; reconciled once per cycle on the aggregator thread. An old and a new schema may
 *       briefly co-exist in the table after a discovery change, so a few spans may not fold quite
 *       as expected for one cycle -- acceptable, because updating the config was always
 *       fundamentally racy against in-flight spans.
 *   <li>{@link datadog.trace.common.metrics.AdditionalTagsSchema} -- local-config span-derived
 *       tags, fixed once at construction.
 * </ul>
 *
 * Each {@link datadog.trace.common.metrics.SpanSnapshot} captures its own schema reference at
 * capture time, so producer and aggregator agree on tag indexing even if the current schema is
 * swapped out between capture and consumption.
 */
package datadog.trace.common.metrics;
