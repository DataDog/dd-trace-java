package datadog.trace.core.monitor;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;

import com.timgroup.statsd.StatsDClient;
import datadog.trace.common.writer.ddagent.DDAgentApi;
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

  private static final String[] USER_DROP_TAG = new String[] {"priority:user_drop"};
  private static final String[] USER_KEEP_TAG = new String[] {"priority:user_keep"};
  private static final String[] SAMPLER_DROP_TAG = new String[] {"priority:sampler_drop"};
  private static final String[] SAMPLER_KEEP_TAG = new String[] {"priority:sampler_keep"};
  private static final String[] UNSET_TAG = new String[] {"priority:unset"};

  private static String[] samplingPriorityTag(int samplingPriority) {
    switch (samplingPriority) {
      case USER_DROP:
        return USER_DROP_TAG;
      case USER_KEEP:
        return USER_KEEP_TAG;
      case SAMPLER_DROP:
        return SAMPLER_DROP_TAG;
      case SAMPLER_KEEP:
        return SAMPLER_KEEP_TAG;
      default:
        return UNSET_TAG;
    }
  }

  private final StatsDClient statsd;

  public Monitor(final StatsDClient statsd) {
    this.statsd = statsd;
  }

  public void onStart(final int queueCapacity) {
    statsd.recordGaugeValue("queue.max_length", queueCapacity);
  }

  public void onShutdown(final boolean flushSuccess) {}

  public void onPublish(final List<DDSpan> trace, int samplingPriority) {
    statsd.incrementCounter("queue.accepted", samplingPriorityTag(samplingPriority));
    statsd.count("queue.accepted_lengths", trace.size());
  }

  public void onFailedPublish(int samplingPriority) {
    statsd.incrementCounter("queue.dropped", samplingPriorityTag(samplingPriority));
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
}
