package datadog.trace.common.sampling;

import datadog.trace.core.DDSpan;

public interface PrioritySampler {
  void setSamplingPriority(DDSpan span);
}
