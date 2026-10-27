package datadog.trace.agent.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BootstrapNullnessTest {
  @Test
  void bootstrapTypeAnnotationsAreReadable() throws ReflectiveOperationException {
    assertNull(Context.class.getClassLoader());
    assertNull(Nullable.class.getClassLoader());
    assertNotNull(
        Context.class
            .getMethod("get", ContextKey.class)
            .getAnnotatedReturnType()
            .getAnnotation(Nullable.class));
    assertNotNull(mock(AgentSpan.class));
  }
}
