package datadog.trace.core.monitor;

import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.opg.OrgGuard;
import java.util.List;

/**
 * Callback for monitoring the health of the DDAgentWriter. Provides hooks for major lifecycle
 * events...
 *
 * <ul>
 *   <li>start
 *   <li>shutdown
 *   <li>publishing to disruptor
 *   <li>serializing
 *   <li>sending to agent
 * </ul>
 */
public abstract class HealthMetrics implements AutoCloseable {
  public static HealthMetrics NO_OP = new HealthMetrics() {};

  public void start() {}

  public void onStart(final int queueCapacity) {}

  public void onShutdown(final boolean flushSuccess) {}

  public void onPublish(final List<DDSpan> trace, final int samplingPriority) {}

  public void onFailedPublish(final int samplingPriority, final int spanCount) {}

  public void onPartialPublish(final int numberOfDroppedSpans) {}

  public void onScheduleFlush(final boolean previousIncomplete) {}

  public void onFlush(final boolean early) {}

  public void onPartialFlush(final int sizeInBytes) {}

  public void onSingleSpanSample() {}

  public void onSingleSpanUnsampled() {}

  public void onSerialize(final int serializedSizeInBytes) {}

  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {}

  public void onCreateSpan() {}

  public void onFinishSpan() {}

  public void onCreateTrace() {}

  public void onScopeCloseError(boolean manual) {}

  public void onCaptureContinuation() {}

  public void onCancelContinuation() {}

  public void onFinishContinuation() {}

  public void onActivateScope() {}

  public void onCloseScope() {}

  public void onScopeStackOverflow() {}

  /**
   * Reports that the Org Propagation Guard dropped the inbound Datadog context for an extracted
   * trace.
   */
  public void onOrgGuardEnforce(OrgGuard.Reason reason) {}

  public void onSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {}

  public void onFailedSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {}

  public void onLongRunningUpdate(final int dropped, final int write, final int expired) {}

  /**
   * Report that a trace has been used to compute client stats.
   *
   * @param countedSpan the number of spans used for the stat computation
   * @param totalSpan the number of total spans in the trace
   * @param dropped true if the trace can be dropped. Note: the PayloadDispatcher also count this.
   *     However, this counter will report how many p0 dropped we could achieve before that the span
   *     got sampled.
   */
  public void onClientStatTraceComputed(
      final int countedSpan, final int totalSpan, boolean dropped) {}

  public void onClientStatPayloadSent() {}

  public void onClientStatErrorReceived() {}

  public void onClientStatDowngraded() {}

  public void onStatsAggregateDropped() {}

  /**
   * Reports a single span whose stats snapshot was dropped because the aggregator inbox was full.
   */
  public void onStatsInboxFull() {}

  /**
   * Reports a batch of {@code count} tag values collapsed into the {@code blocked_by_tracer}
   * sentinel for {@code tag} during the just-completed reporting cycle (per-tag cardinality budget
   * exhausted, or per-value length cap exceeded). Called from the aggregator thread once per
   * affected tag at cycle reset, so the implementation can do a single counter update rather than
   * one per blocked value.
   */
  public void onTagCardinalityBlocked(String[] statsDTag, long count) {}

  /**
   * @return Human-readable summary of the current health metrics.
   */
  public String summary() {
    return "";
  }

  @Override
  public void close() {}
}
