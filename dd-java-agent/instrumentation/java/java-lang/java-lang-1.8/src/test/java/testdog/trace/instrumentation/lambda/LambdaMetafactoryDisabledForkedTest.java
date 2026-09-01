package testdog.trace.instrumentation.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.lambda.enabled", value = "false")
public class LambdaMetafactoryDisabledForkedTest extends AbstractInstrumentationTest {

  @Test
  void runnableLambdaIsNotFieldInjected() {
    Runnable lambda = () -> {};

    assertFalse(lambda instanceof FieldBackedContextAccessor);
  }
}
