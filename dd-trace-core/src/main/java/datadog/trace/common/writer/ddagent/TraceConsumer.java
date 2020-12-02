package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import java.util.List;

public interface TraceConsumer {
  void accept(List<? extends CoreSpan<?>> trace);

  void flush();
}
