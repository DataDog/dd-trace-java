package testdog.trace.instrumentation.lambda;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static testdog.trace.instrumentation.lambda.TestRunnableLambdaInstrumentation.ADVICE_MARKER_FIELD;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.lambda.enabled", value = "true")
@WithConfig(key = "experimental.defer.integrations.until", value = "30s")
public class LambdaMetafactoryDeferredForkedTest extends AbstractInstrumentationTest {

  @Test
  void generatedLambdaIsTransformedWhileRegularMatchingIsDeferred() throws Exception {
    Runnable lambda = () -> {};

    assertTrue(lambda instanceof FieldBackedContextAccessor);
    assertTrue(lambda.getClass().getDeclaredField(ADVICE_MARKER_FIELD).isSynthetic());
  }
}
