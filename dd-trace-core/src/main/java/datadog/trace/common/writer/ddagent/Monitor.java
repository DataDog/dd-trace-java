package datadog.trace.common.writer.ddagent;

import com.timgroup.statsd.StatsDClient;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
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
    this.statsd = statsd;
  }

  public void onStart(final DDAgentWriter agentWriter) {
    statsd.recordGaugeValue("queue.max_length", agentWriter.getDisruptorCapacity());
  }

  public void onShutdown(final DDAgentWriter agentWriter, final boolean flushSuccess) {}

  public void onPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {
    statsd.incrementCounter("queue.accepted");
    statsd.count("queue.accepted_lengths", trace.size());
  }

  public void onFailedPublish(final DDAgentWriter agentWriter, final List<DDSpan> trace) {
    statsd.incrementCounter("queue.dropped");
  }

  public void onScheduleFlush(final DDAgentWriter agentWriter, final boolean previousIncomplete) {
    // not recorded
  }

  public void onFlush(final DDAgentWriter agentWriter, final boolean early) {}

  public void onSerialize(final int serializedSizeInBytes) {
    // DQH - Because of Java tracer's 2 phase acceptance and serialization scheme, this doesn't
    // map precisely
    statsd.count("queue.accepted_size", serializedSizeInBytes);
  }

  public void onFailedSerialize(
      final DDAgentWriter agentWriter, final List<DDSpan> trace, final Throwable optionalCause) {
    // TODO - DQH - make a new stat for serialization failure -- or maybe count this towards
    // api.errors???
  }

  public void onSend(
      final DDAgentWriter agentWriter,
      final int representativeCount,
      final int sizeInBytes,
      final DDAgentApi.Response response) {
    onSendAttempt(agentWriter, representativeCount, sizeInBytes, response);
  }

  public void onFailedSend(
      final DDAgentWriter agentWriter,
      final int representativeCount,
      final int sizeInBytes,
      final DDAgentApi.Response response) {
    onSendAttempt(agentWriter, representativeCount, sizeInBytes, response);
  }

  private void onSendAttempt(
      final DDAgentWriter agentWriter,
      final int representativeCount,
      final int sizeInBytes,
      final DDAgentApi.Response response) {
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
}
