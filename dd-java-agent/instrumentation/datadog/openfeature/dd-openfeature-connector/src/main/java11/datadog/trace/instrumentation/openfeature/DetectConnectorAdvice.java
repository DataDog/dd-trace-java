package datadog.trace.instrumentation.openfeature;

import com.datadog.openfeature.internal.connector.Connector;
import net.bytebuddy.asm.Advice;

public class DetectConnectorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void detect(@Advice.Return(readOnly = false) Connector connector) {
    connector = JavaAgentConnector.connect(connector);
  }
}
