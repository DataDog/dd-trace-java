package datadog.trace.core.propagation;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.SamplingMechanism;

public class LambdaContext extends ExtractedContext {

  public LambdaContext(final String traceId, final String spanId, final int samplingPriority) {
    super(
        DDId.from(traceId),
        null,
        samplingPriority,
        SamplingMechanism.DEFAULT,
        null,
        0,
        null,
        null);
  }
}
