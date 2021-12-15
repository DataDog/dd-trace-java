package datadog.trace.test.agent.decoder;

import java.util.List;

public interface DecodedTrace {
  List<DecodedSpan> getSpans();
}
