package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface PrioritizationStrategy {

  enum PublishResult {
    ENQUEUED_FOR_SERIALIZATION,
    ENQUEUED_FOR_SINGLE_SPAN_SAMPLING,
    DROPPED_BY_POLICY,
    DROPPED_BUFFER_OVERFLOW
  }

  <T extends CoreSpan<T>> PublishResult publish(T root, int priority, List<T> trace);

  boolean flush(long timeout, TimeUnit timeUnit);
}
