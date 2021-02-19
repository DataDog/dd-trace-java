package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface PrioritizationStrategy {

  <T extends CoreSpan<T>> boolean publish(T root, int priority, List<T> trace);

  boolean flush(long timeout, TimeUnit timeUnit);
}
