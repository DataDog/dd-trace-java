package datadog.trace.core.monitor;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.common.writer.RemoteApi;
import java.util.concurrent.atomic.LongAdder;

/**
 * A faithful reconstruction of the pre-{@code Accumulator} {@code TracerHealthMetrics} -- one
 * {@link LongAdder} field per counter, hand-rolled switch statements, a hand-concatenated {@code
 * summary()} -- as it stood at {@code 77964b3996} (the last commit on {@code master} before the
 * migration), restricted to the exact methods {@link TracerHealthMetricsBenchmark} exercises. Kept
 * as a standalone class here (not resurrected via checkout) purely for a same-run, same-JVM
 * before/after comparison; it is not wired into anything and should never be.
 */
class LegacyTracerHealthMetrics {

  private final LongAdder apiRequests = new LongAdder();
  private final LongAdder apiErrors = new LongAdder();
  private final LongAdder apiResponsesOK = new LongAdder();

  private final LongAdder userDropEnqueuedTraces = new LongAdder();
  private final LongAdder userKeepEnqueuedTraces = new LongAdder();
  private final LongAdder samplerDropEnqueuedTraces = new LongAdder();
  private final LongAdder samplerKeepEnqueuedTraces = new LongAdder();
  private final LongAdder unsetPriorityEnqueuedTraces = new LongAdder();

  private final LongAdder userDropDroppedTraces = new LongAdder();
  private final LongAdder userKeepDroppedTraces = new LongAdder();
  private final LongAdder samplerDropDroppedTraces = new LongAdder();
  private final LongAdder samplerKeepDroppedTraces = new LongAdder();
  private final LongAdder serialFailedDroppedTraces = new LongAdder();
  private final LongAdder unsetPriorityDroppedTraces = new LongAdder();

  private final LongAdder userDropDroppedSpans = new LongAdder();
  private final LongAdder userKeepDroppedSpans = new LongAdder();
  private final LongAdder samplerDropDroppedSpans = new LongAdder();
  private final LongAdder samplerKeepDroppedSpans = new LongAdder();
  private final LongAdder serialFailedDroppedSpans = new LongAdder();
  private final LongAdder unsetPriorityDroppedSpans = new LongAdder();

  private final LongAdder enqueuedSpans = new LongAdder();
  private final LongAdder enqueuedBytes = new LongAdder();
  private final LongAdder createdTraces = new LongAdder();
  private final LongAdder createdSpans = new LongAdder();
  private final LongAdder finishedSpans = new LongAdder();
  private final LongAdder flushedTraces = new LongAdder();
  private final LongAdder flushedBytes = new LongAdder();
  private final LongAdder partialTraces = new LongAdder();
  private final LongAdder partialBytes = new LongAdder();
  private final LongAdder clientSpansWithoutContext = new LongAdder();

  private final LongAdder singleSpanSampled = new LongAdder();
  private final LongAdder singleSpanUnsampled = new LongAdder();

  private final LongAdder capturedContinuations = new LongAdder();
  private final LongAdder cancelledContinuations = new LongAdder();
  private final LongAdder finishedContinuations = new LongAdder();

  private final LongAdder activatedScopes = new LongAdder();
  private final LongAdder closedScopes = new LongAdder();
  private final LongAdder scopeStackOverflow = new LongAdder();
  private final LongAdder scopeCloseErrors = new LongAdder();
  private final LongAdder userScopeCloseErrors = new LongAdder();

  private final LongAdder longRunningTracesWrite = new LongAdder();
  private final LongAdder longRunningTracesDropped = new LongAdder();
  private final LongAdder longRunningTracesExpired = new LongAdder();

  private final LongAdder clientStatsProcessedSpans = new LongAdder();
  private final LongAdder clientStatsProcessedTraces = new LongAdder();
  private final LongAdder clientStatsP0DroppedSpans = new LongAdder();
  private final LongAdder clientStatsP0DroppedTraces = new LongAdder();
  private final LongAdder clientStatsRequests = new LongAdder();
  private final LongAdder clientStatsErrors = new LongAdder();
  private final LongAdder clientStatsDowngrades = new LongAdder();

  private final LongAdder statsAggregateDropped = new LongAdder();
  private final LongAdder statsInboxFull = new LongAdder();

  private final StatsDClient statsd;

  LegacyTracerHealthMetrics(StatsDClient statsd) {
    this.statsd = statsd;
  }

  void onCreateSpan() {
    createdSpans.increment();
  }

  void onFailedPublish(final int samplingPriority, final int spanCount) {
    switch (samplingPriority) {
      case USER_DROP:
        userDropDroppedSpans.add(spanCount);
        userDropDroppedTraces.increment();
        break;
      case USER_KEEP:
        userKeepDroppedSpans.add(spanCount);
        userKeepDroppedTraces.increment();
        break;
      case SAMPLER_DROP:
        samplerDropDroppedSpans.add(spanCount);
        samplerDropDroppedTraces.increment();
        break;
      case SAMPLER_KEEP:
        samplerKeepDroppedSpans.add(spanCount);
        samplerKeepDroppedTraces.increment();
        break;
      default:
        unsetPriorityDroppedSpans.add(spanCount);
        unsetPriorityDroppedTraces.increment();
    }
  }

  void onPartialPublish(final int numberOfDroppedSpans) {
    partialTraces.increment();
    samplerDropDroppedSpans.add(numberOfDroppedSpans);
  }

  void onSend(final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    onSendAttempt(traceCount, sizeInBytes, response);
  }

  private void onSendAttempt(
      final int traceCount, final int sizeInBytes, final RemoteApi.Response response) {
    apiRequests.increment();
    flushedTraces.add(traceCount);
    flushedBytes.add(sizeInBytes);

    if (response.exception().isPresent()) {
      apiErrors.increment();
    }

    int status = response.status().orElse(0);
    if (status != 0) {
      if (200 == status) {
        apiResponsesOK.increment();
      } else {
        statsd.incrementCounter("api.responses.total", "status:" + status);
      }
    }
  }

  String summary() {
    return "apiRequests="
        + apiRequests.sum()
        + "\napiErrors="
        + apiErrors.sum()
        + "\napiResponsesOK="
        + apiResponsesOK.sum()
        + "\n"
        + "\nuserDropEnqueuedTraces="
        + userDropEnqueuedTraces.sum()
        + "\nuserKeepEnqueuedTraces="
        + userKeepEnqueuedTraces.sum()
        + "\nsamplerDropEnqueuedTraces="
        + samplerDropEnqueuedTraces.sum()
        + "\nsamplerKeepEnqueuedTraces="
        + samplerKeepEnqueuedTraces.sum()
        + "\nunsetPriorityEnqueuedTraces="
        + unsetPriorityEnqueuedTraces.sum()
        + "\n"
        + "\nuserDropDroppedTraces="
        + userDropDroppedTraces.sum()
        + "\nuserKeepDroppedTraces="
        + userKeepDroppedTraces.sum()
        + "\nsamplerDropDroppedTraces="
        + samplerDropDroppedTraces.sum()
        + "\nsamplerKeepDroppedTraces="
        + samplerKeepDroppedTraces.sum()
        + "\nserialFailedDroppedTraces="
        + serialFailedDroppedTraces.sum()
        + "\nunsetPriorityDroppedTraces="
        + unsetPriorityDroppedTraces.sum()
        + "\n"
        + "\nuserDropDroppedSpans="
        + userDropDroppedSpans.sum()
        + "\nuserKeepDroppedSpans="
        + userKeepDroppedSpans.sum()
        + "\nsamplerDropDroppedSpans="
        + samplerDropDroppedSpans.sum()
        + "\nsamplerKeepDroppedSpans="
        + samplerKeepDroppedSpans.sum()
        + "\nserialFailedDroppedSpans="
        + serialFailedDroppedSpans.sum()
        + "\nunsetPriorityDroppedSpans="
        + unsetPriorityDroppedSpans.sum()
        + "\n"
        + "\nenqueuedSpans="
        + enqueuedSpans.sum()
        + "\nenqueuedBytes="
        + enqueuedBytes.sum()
        + "\ncreatedTraces="
        + createdTraces.sum()
        + "\ncreatedSpans="
        + createdSpans.sum()
        + "\nfinishedSpans="
        + finishedSpans.sum()
        + "\nflushedTraces="
        + flushedTraces.sum()
        + "\nflushedBytes="
        + flushedBytes.sum()
        + "\npartialTraces="
        + partialTraces.sum()
        + "\npartialBytes="
        + partialBytes.sum()
        + "\n"
        + "\nclientSpansWithoutContext="
        + clientSpansWithoutContext.sum()
        + "\n"
        + "\nsingleSpanSampled="
        + singleSpanSampled.sum()
        + "\nsingleSpanUnsampled="
        + singleSpanUnsampled.sum()
        + "\n"
        + "\ncapturedContinuations="
        + capturedContinuations.sum()
        + "\ncancelledContinuations="
        + cancelledContinuations.sum()
        + "\nfinishedContinuations="
        + finishedContinuations.sum()
        + "\n"
        + "\nactivatedScopes="
        + activatedScopes.sum()
        + "\nclosedScopes="
        + closedScopes.sum()
        + "\nscopeStackOverflow="
        + scopeStackOverflow.sum()
        + "\nscopeCloseErrors="
        + scopeCloseErrors.sum()
        + "\nuserScopeCloseErrors="
        + userScopeCloseErrors.sum()
        + "\n"
        + "\nlongRunningTracesWrite="
        + longRunningTracesWrite.sum()
        + "\nlongRunningTracesDropped="
        + longRunningTracesDropped.sum()
        + "\nlongRunningTracesExpired="
        + longRunningTracesExpired.sum()
        + "\n"
        + "\nclientStatsRequests="
        + clientStatsRequests.sum()
        + "\nclientStatsErrors="
        + clientStatsErrors.sum()
        + "\nclientStatsDowngrades="
        + clientStatsDowngrades.sum()
        + "\nclientStatsP0DroppedSpans="
        + clientStatsP0DroppedSpans.sum()
        + "\nclientStatsP0DroppedTraces="
        + clientStatsP0DroppedTraces.sum()
        + "\nclientStatsProcessedSpans="
        + clientStatsProcessedSpans.sum()
        + "\nclientStatsProcessedTraces="
        + clientStatsProcessedTraces.sum()
        + "\nstatsAggregateDropped="
        + statsAggregateDropped.sum()
        + "\nstatsInboxFull="
        + statsInboxFull.sum();
  }
}
