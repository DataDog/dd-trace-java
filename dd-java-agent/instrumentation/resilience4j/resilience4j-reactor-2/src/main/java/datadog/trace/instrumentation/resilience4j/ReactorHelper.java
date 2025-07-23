package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Consumer;
import reactor.core.publisher.SignalType;

public class ReactorHelper {

  public static Consumer<SignalType> beforeFinish(AgentSpan span) {
    return signalType -> span.finish();
  }
}
