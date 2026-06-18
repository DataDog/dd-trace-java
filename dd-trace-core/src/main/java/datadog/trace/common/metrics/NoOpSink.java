package datadog.trace.common.metrics;

import java.nio.ByteBuffer;

/**
 * A {@link Sink} that discards everything. Used by the OTLP trace-metrics export path, where {@link
 * OtlpStatsMetricWriter} sends payloads directly via its own OTLP sender in {@code finishBucket()}
 * rather than through the aggregator's {@code Sink}. {@link ClientStatsAggregator} still
 * requires a {@code Sink} for {@code register()}/backpressure wiring, so this satisfies that
 * contract without performing any I/O.
 */
public final class NoOpSink implements Sink {

  public static final NoOpSink INSTANCE = new NoOpSink();

  private NoOpSink() {}

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {}

  @Override
  public void register(EventListener listener) {}
}
