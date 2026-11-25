import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.junit.jupiter.api.Test;

/** The normal build keeps legacy handler behavior until the final activation PR. */
class LambdaDisabledGateForkedTest extends LambdaHandlerInstrumentationV0Test {
  @Test
  void normalBuildCannotStartNativeInvocations() {
    assertFalse(AgentTracer.get().isNativeLambdaTracingEnabled());
    assertNull(AgentTracer.get().startLambdaInvocation("{}", "request", "function", "1", null));
  }
}
