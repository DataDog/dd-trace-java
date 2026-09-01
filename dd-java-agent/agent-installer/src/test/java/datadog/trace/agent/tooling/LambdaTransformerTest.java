package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.bootstrap.instrumentation.java.lang.invoke.LambdaTransformer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LambdaTransformerTest {

  @Test
  void onlyTransformsEnabledInterfaces() {
    byte[] transformedBytes = new byte[0];
    AtomicBoolean transformed = new AtomicBoolean();
    LambdaTransformer delegate =
        (className, targetClass, classBytes, interfaceName) -> {
          transformed.set(true);
          return transformedBytes;
        };
    LambdaTransformer transformer =
        AgentInstaller.filterLambdaTransformer(delegate, new String[] {Runnable.class.getName()});

    assertSame(
        transformedBytes,
        transformer.transform("test/Lambda", Object.class, new byte[0], Runnable.class.getName()));
    transformed.set(false);
    assertNull(transformer.transform("test/Lambda", Object.class, new byte[0], "other.Interface"));
    assertFalse(transformed.get());
  }
}
