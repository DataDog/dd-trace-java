package testdog.trace.instrumentation.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static testdog.trace.instrumentation.lambda.TestRunnableLambdaInstrumentation.ADVICE_MARKER_FIELD;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Lambda integration tests outside the ignored {@code datadog.*} prefix. */
@WithConfig(key = "trace.lambda.enabled", value = "true")
public class LambdaMetafactoryIntegrationTest extends AbstractInstrumentationTest {

  @Test
  void registeredLambdaReceivesItsInstrumentationTransformations() {
    // Link after the agent is installed.
    Runnable lambda = () -> {};

    assertTrue(
        lambda instanceof FieldBackedContextAccessor,
        "test instrumentation should field-inject Runnable lambdas");
    assertTrue(
        hasAdviceMarker(lambda),
        "test instrumentation should apply its own type advice to Runnable lambdas");
  }

  @Test
  void testInstrumentationHandlesRunnableCaptureShapes() {
    AtomicInteger counter = new AtomicInteger();
    int delta = 7;
    Runnable[] lambdas = {() -> {}, counter::incrementAndGet, () -> counter.addAndGet(delta)};

    for (Runnable lambda : lambdas) {
      assertTrue(
          lambda instanceof FieldBackedContextAccessor,
          "every Runnable lambda shape should be field-injected");
      assertTrue(
          hasAdviceMarker(lambda),
          "every Runnable lambda shape should receive the test instrumentation advice");
      lambda.run();
    }
    assertEquals(8, counter.get());
  }

  @Test
  void unregisteredLambdaInterfaceIsNotTransformed() {
    Supplier<Object> lambda = Object::new;

    assertFalse(
        lambda instanceof FieldBackedContextAccessor,
        "only interfaces registered by a lambda instrumentation should be transformed");
    assertFalse(
        hasAdviceMarker(lambda),
        "an unregistered lambda interface should not receive the test instrumentation advice");
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
