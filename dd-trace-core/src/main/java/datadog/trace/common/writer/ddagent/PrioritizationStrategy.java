package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface PrioritizationStrategy {

  boolean publish(int priority, List<DDSpan> trace);

  boolean flush(long timeout, TimeUnit timeUnit);
}
