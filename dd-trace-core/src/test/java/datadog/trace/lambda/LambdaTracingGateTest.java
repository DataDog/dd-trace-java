package datadog.trace.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import org.junit.jupiter.api.Test;

class LambdaTracingGateTest extends DDCoreJavaSpecification {
  @Test
  void nativeTracingIsHardDisabled() {
    CoreTracer tracer = tracerBuilder().build();
    try {
      assertFalse(tracer.isNativeLambdaTracingEnabled());
      assertFalse(AgentTracer.NOOP_TRACER.isNativeLambdaTracingEnabled());
    } finally {
      tracer.close();
    }
  }
}
