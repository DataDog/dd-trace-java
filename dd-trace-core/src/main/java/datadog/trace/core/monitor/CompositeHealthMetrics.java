package datadog.trace.core.monitor;

import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.opg.OrgGuard;
import java.util.List;

/**
 * Forwards every {@link HealthMetrics} callback to each delegate in order, so each event keeps a
 * single hook however many sinks are enabled. {@code CompositeHealthMetricsTest} checks that every
 * overridable callback is forwarded.
 */
public final class CompositeHealthMetrics extends HealthMetrics {
  private final HealthMetrics[] delegates;

  public CompositeHealthMetrics(HealthMetrics... delegates) {
    this.delegates = delegates.clone();
  }

  /**
   * Starts every delegate, even if an earlier one fails, then rethrows the first failure with any
   * later ones suppressed.
   */
  @Override
  public void start() {
    RuntimeException failure = null;
    for (HealthMetrics delegate : delegates) {
      try {
        delegate.start();
      } catch (RuntimeException e) {
        failure = addFailure(failure, e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  @Override
  public void onStart(final int queueCapacity) {
    for (HealthMetrics delegate : delegates) {
      delegate.onStart(queueCapacity);
    }
  }

  @Override
  public void onShutdown(final boolean flushSuccess) {
    for (HealthMetrics delegate : delegates) {
      delegate.onShutdown(flushSuccess);
    }
  }

  @Override
  public void onPublish(final List<DDSpan> trace, final int samplingPriority) {
    for (HealthMetrics delegate : delegates) {
      delegate.onPublish(trace, samplingPriority);
    }
  }

  @Override
  public void onFailedPublish(final int samplingPriority, final int spanCount) {
    for (HealthMetrics delegate : delegates) {
      delegate.onFailedPublish(samplingPriority, spanCount);
    }
  }

  @Override
  public void onPartialPublish(final int numberOfDroppedSpans) {
    for (HealthMetrics delegate : delegates) {
      delegate.onPartialPublish(numberOfDroppedSpans);
    }
  }

  @Override
  public void onScheduleFlush(final boolean previousIncomplete) {
    for (HealthMetrics delegate : delegates) {
      delegate.onScheduleFlush(previousIncomplete);
    }
  }

  @Override
  public void onFlush(final boolean early) {
    for (HealthMetrics delegate : delegates) {
      delegate.onFlush(early);
    }
  }

  @Override
  public void onPartialFlush(final int sizeInBytes) {
    for (HealthMetrics delegate : delegates) {
      delegate.onPartialFlush(sizeInBytes);
    }
  }

  @Override
  public void onSingleSpanSample() {
    for (HealthMetrics delegate : delegates) {
      delegate.onSingleSpanSample();
    }
  }

  @Override
  public void onSingleSpanUnsampled() {
    for (HealthMetrics delegate : delegates) {
      delegate.onSingleSpanUnsampled();
    }
  }

  @Override
  public void onSerialize(final int serializedSizeInBytes) {
    for (HealthMetrics delegate : delegates) {
      delegate.onSerialize(serializedSizeInBytes);
    }
  }

  @Override
  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    for (HealthMetrics delegate : delegates) {
      delegate.onFailedSerialize(trace, optionalCause);
    }
  }

  @Override
  public void onCreateSpan(final DDSpan span) {
    for (HealthMetrics delegate : delegates) {
      delegate.onCreateSpan(span);
    }
  }

  @Override
  public void onEndSpan() {
    for (HealthMetrics delegate : delegates) {
      delegate.onEndSpan();
    }
  }

  @Override
  public void onFinishSpan() {
    for (HealthMetrics delegate : delegates) {
      delegate.onFinishSpan();
    }
  }

  @Override
  public void onCreateTrace() {
    for (HealthMetrics delegate : delegates) {
      delegate.onCreateTrace();
    }
  }

  @Override
  public void onScopeCloseError(boolean manual) {
    for (HealthMetrics delegate : delegates) {
      delegate.onScopeCloseError(manual);
    }
  }

  @Override
  public void onCaptureContinuation() {
    for (HealthMetrics delegate : delegates) {
      delegate.onCaptureContinuation();
    }
  }

  @Override
  public void onCancelContinuation() {
    for (HealthMetrics delegate : delegates) {
      delegate.onCancelContinuation();
    }
  }

  @Override
  public void onFinishContinuation() {
    for (HealthMetrics delegate : delegates) {
      delegate.onFinishContinuation();
    }
  }

  @Override
  public void onActivateScope() {
    for (HealthMetrics delegate : delegates) {
      delegate.onActivateScope();
    }
  }

  @Override
  public void onCloseScope() {
    for (HealthMetrics delegate : delegates) {
      delegate.onCloseScope();
    }
  }

  @Override
  public void onScopeStackOverflow() {
    for (HealthMetrics delegate : delegates) {
      delegate.onScopeStackOverflow();
    }
  }

  @Override
  public void onOrgGuardEnforce(OrgGuard.Reason reason) {
    for (HealthMetrics delegate : delegates) {
      delegate.onOrgGuardEnforce(reason);
    }
  }

  @Override
  public void onSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    for (HealthMetrics delegate : delegates) {
      delegate.onSend(traceCount, sizeInBytes, response);
    }
  }

  @Override
  public void onFailedSend(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    for (HealthMetrics delegate : delegates) {
      delegate.onFailedSend(traceCount, sizeInBytes, response);
    }
  }

  @Override
  public void onExportStart(final OtelExporterId exporter, final long itemCount) {
    for (HealthMetrics delegate : delegates) {
      delegate.onExportStart(exporter, itemCount);
    }
  }

  @Override
  public void onExportEnd(
      final OtelExporterId exporter,
      final long itemCount,
      final long durationNanos,
      final RemoteApi.Response response,
      final Throwable error) {
    for (HealthMetrics delegate : delegates) {
      delegate.onExportEnd(exporter, itemCount, durationNanos, response, error);
    }
  }

  @Override
  public void onMetricCollection(
      final ComponentId reader, final long durationNanos, final Throwable error) {
    for (HealthMetrics delegate : delegates) {
      delegate.onMetricCollection(reader, durationNanos, error);
    }
  }

  @Override
  public void onLongRunningUpdate(final int dropped, final int write, final int expired) {
    for (HealthMetrics delegate : delegates) {
      delegate.onLongRunningUpdate(dropped, write, expired);
    }
  }

  @Override
  public void onClientStatTraceComputed(
      final int countedSpan, final int totalSpan, boolean dropped) {
    for (HealthMetrics delegate : delegates) {
      delegate.onClientStatTraceComputed(countedSpan, totalSpan, dropped);
    }
  }

  @Override
  public void onClientStatPayloadSent() {
    for (HealthMetrics delegate : delegates) {
      delegate.onClientStatPayloadSent();
    }
  }

  @Override
  public void onClientStatErrorReceived() {
    for (HealthMetrics delegate : delegates) {
      delegate.onClientStatErrorReceived();
    }
  }

  @Override
  public void onClientStatDowngraded() {
    for (HealthMetrics delegate : delegates) {
      delegate.onClientStatDowngraded();
    }
  }

  @Override
  public void onStatsAggregateDropped() {
    for (HealthMetrics delegate : delegates) {
      delegate.onStatsAggregateDropped();
    }
  }

  @Override
  public void onStatsInboxFull() {
    for (HealthMetrics delegate : delegates) {
      delegate.onStatsInboxFull();
    }
  }

  @Override
  public void onTagCardinalityBlocked(String[] statsDTag, long count) {
    for (HealthMetrics delegate : delegates) {
      delegate.onTagCardinalityBlocked(statsDTag, count);
    }
  }

  /** Joins the non-empty delegate summaries, one per line. */
  @Override
  public String summary() {
    StringBuilder summary = new StringBuilder();
    for (HealthMetrics delegate : delegates) {
      String delegateSummary = delegate.summary();
      if (delegateSummary != null && !delegateSummary.isEmpty()) {
        if (summary.length() > 0) {
          summary.append('\n');
        }
        summary.append(delegateSummary);
      }
    }
    return summary.toString();
  }

  /**
   * Closes every delegate, even if an earlier one fails, then rethrows the first failure with any
   * later ones suppressed.
   */
  @Override
  public void close() {
    RuntimeException failure = null;
    for (HealthMetrics delegate : delegates) {
      try {
        delegate.close();
      } catch (RuntimeException e) {
        failure = addFailure(failure, e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException addFailure(RuntimeException first, RuntimeException next) {
    if (first == null) {
      return next;
    }
    first.addSuppressed(next);
    return first;
  }
}
