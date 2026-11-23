package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface PrioritizationStrategy {

  enum PublishResult {
    ENQUEUED_FOR_SERIALIZATION,
    ENQUEUED_FOR_SINGLE_SPAN_SAMPLING,
    DROPPED_BY_POLICY,
    /**
     * A trace that sampling decided to keep could not be enqueued because the queue was full. The
     * trace is lost and will be missing from the UI.
     */
    DROPPED_BUFFER_OVERFLOW,
    /**
     * A trace that sampling already decided to drop could not be enqueued because the queue was
     * full. No kept trace is lost. Such traces are only enqueued at all when the agent computes
     * trace stats itself, so the sole consequence is a small loss of accuracy in agent-computed
     * stats; with client-side stats enabled they are discarded before reaching a queue.
     */
    DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT,
    /**
     * A trace that sampling decided to drop could not be offered to the single span sampling queue
     * because that queue was full. Unlike {@link #DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT}, this is not
     * a stats-only loss: the trace's spans were candidates to be individually kept by single span
     * sampling, so losing them can mean losing spans that would otherwise have reached the UI.
     */
    DROPPED_BUFFER_OVERFLOW_SINGLE_SPAN
  }

  <T extends CoreSpan<T>> PublishResult publish(T root, int priority, List<T> trace);

  boolean flush(long timeout, TimeUnit timeUnit);
}
