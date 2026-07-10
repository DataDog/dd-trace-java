package datadog.trace.test.agent.decoder;

import java.util.List;

public interface DecodedTrace {
  List<DecodedSpan> getSpans();

  // Payload v1.0 has sampling priority on trace level.
  default Integer getSamplingPriority() {
    return null;
  }
}
