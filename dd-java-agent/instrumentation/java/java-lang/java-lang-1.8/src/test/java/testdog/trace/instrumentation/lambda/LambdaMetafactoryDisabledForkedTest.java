package testdog.trace.instrumentation.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static testdog.trace.instrumentation.lambda.TestRunnableLambdaInstrumentation.ADVICE_MARKER_FIELD;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.bootstrap.instrumentation.java.module.JpmsHelper;
import org.junit.jupiter.api.Test;

public class LambdaMetafactoryDisabledForkedTest extends AbstractInstrumentationTest {

  @Test
  void lambdaTransformationIsDisabledByDefault() {
    Runnable lambda = () -> {};

    assertFalse(lambda instanceof FieldBackedContextAccessor);
    assertFalse(hasAdviceMarker(lambda));
    assertFalse(
        JpmsHelper.getAllTriggers().contains("java.lang.invoke.InnerClassLambdaMetafactory"),
        "disabled lambda instrumentation should not register a JPMS clearance trigger");
  }

  private static boolean hasAdviceMarker(Object lambda) {
    try {
      lambda.getClass().getDeclaredField(ADVICE_MARKER_FIELD);
      return true;
    } catch (NoSuchFieldException ignored) {
      return false;
    }
  }
}
