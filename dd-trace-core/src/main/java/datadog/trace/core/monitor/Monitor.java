package datadog.trace.core.monitor;

import com.timgroup.statsd.StatsDClient;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.core.DDSpan;
import datadog.trace.core.util.SpanContextStack;
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
public class Monitor {
  private final StatsDClient statsd;

  public Monitor(final StatsDClient statsd) {
    assert statsd != null;
    this.statsd = statsd;
  }

  public void onStart(final int queueCapacity) {
    statsd.recordGaugeValue("queue.max_length", queueCapacity);
  }

  public void onShutdown(final boolean flushSuccess) {}

  public void onPublish(final List<DDSpan> trace, int samplingPriority) {
    statsd.incrementCounter("queue.accepted", String.valueOf(samplingPriority));
    statsd.count("queue.accepted_lengths", trace.size());
  }

  public void onFailedPublish(int samplingPriority) {
    statsd.incrementCounter("queue.dropped", String.valueOf(samplingPriority));
  }

  public void onScheduleFlush(final boolean previousIncomplete) {
    // not recorded
  }

  public void onFlush(final boolean early) {}

  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    statsd.count("queue.accepted_size", serializedSizeInBytes);
  }

  public void onFailedSerialize(final List<DDSpan> trace, final Throwable optionalCause) {
    // TODO - DQH - make a new stat for serialization failure -- or maybe count this towards
    // api.errors???
  }

  public void onSend(
      final int representativeCount, final int sizeInBytes, final DDAgentApi.Response response) {
    onSendAttempt(representativeCount, sizeInBytes, response);
  }

  public void onFailedSend(
      final int representativeCount, final int sizeInBytes, final DDAgentApi.Response response) {
    onSendAttempt(representativeCount, sizeInBytes, response);
  }

  private void onSendAttempt(
      final int representativeCount, final int sizeInBytes, final DDAgentApi.Response response) {
    statsd.incrementCounter("api.requests");
    statsd.recordGaugeValue("queue.length", representativeCount);
    // TODO: missing queue.spans (# of spans being sent)
    statsd.recordGaugeValue("queue.size", sizeInBytes);

    if (response.exception() != null) {
      // covers communication errors -- both not receiving a response or
      // receiving malformed response (even when otherwise successful)
      statsd.incrementCounter("api.errors");
    }

    if (response.status() != null) {
      statsd.incrementCounter("api.responses", "status: " + response.status());
    }
  }

  public void onSpanContextStack(SpanContextStack.Origin origin) {
    switch (origin) {
      case ROOT:
        break;
      case CLIENT:
        statsd.incrementCounter("span.context-stack", "origin:client");
        break;
    }
  }
}
