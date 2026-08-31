package testdog.trace.instrumentation.lambda;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clojure.java.api.Clojure;
import clojure.lang.AFn;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.FieldBackedContextAccessor;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.lambda.enabled", value = "true")
@WithConfig(key = "trace.runnable.enabled", value = "false")
public class ClojureAFnIntegrationTest extends AbstractInstrumentationTest {

  @Test
  void afnIsNotFieldInjected() {
    // Runnable instrumentation can be disabled to avoid inflating every AFn; see
    // https://github.com/DataDog/dd-trace-java/pull/2925.
    Object function = Clojure.var("clojure.core", "eval").invoke(Clojure.read("(fn [] nil)"));

    assertTrue(function instanceof AFn);
    assertTrue(function instanceof Runnable);
    assertFalse(function instanceof FieldBackedContextAccessor);
  }
}
