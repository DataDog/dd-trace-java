package datadog.smoketest.springboot;

import static datadog.smoketest.springboot.OpenFeatureFixtures.agentlessApp;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evaluate;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evaluationCase;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evpProxy;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.SmokeServerApp;
import datadog.smoketest.backend.EvpProxy;
import datadog.smoketest.springboot.OpenFeatureFixtures.Evaluation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SDK with the Datadog Java agent flushes its pending events when the application exits. */
abstract class AbstractOpenFeatureShutdownSmokeTest {
  private static final String FLAG_EVALUATION_PATH = "api/v2/flagevaluation";

  static SmokeServerApp shutdownApp(String name, boolean tracing) {
    return agentlessApp(name).jvmArgs("-Ddd.trace.enabled=" + tracing).skipTelemetryCheck().build();
  }

  /**
   * Gets the application under test.
   *
   * @return The application under test.
   */
  abstract SmokeServerApp app();

  @Test
  void flushesPendingFlagEvaluationsAtExit() throws IOException {
    // The first evaluation is flushed within the 10 seconds flush interval, which then restarts:
    // the next evaluation stays pending until the application exits.
    evaluateCase("test-case-boolean-false-assignment.json");
    evpProxy(app())
        .waitForRequest(
            request ->
                FLAG_EVALUATION_PATH.equals(request.path())
                    && evaluatedFlags(request).contains("boolean-false-assignment"),
            20);
    evaluateCase("test-case-boolean-one-of-matches.json");
    assertFalse(evaluatedFlags().contains("boolean-one-of-matches"), "the evaluation is pending");

    app().terminate(30, SECONDS);

    assertTrue(evaluatedFlags().contains("boolean-one-of-matches"), "the evaluation is flushed");
    for (EvpProxy.Request request : evpProxy(app()).requests(FLAG_EVALUATION_PATH)) {
      assertEquals("dd-trace-java", request.header("DD-EVP-ORIGIN"), "origin");
      String originVersion = request.header("DD-EVP-ORIGIN-VERSION");
      assertTrue(originVersion != null && !originVersion.isEmpty(), "origin version");
    }
  }

  private void evaluateCase(String evaluationCaseFile) throws IOException {
    Evaluation evaluation = evaluate(app(), evaluationCase(evaluationCaseFile).request);
    assertEquals(200, evaluation.code, evaluation.body);
  }

  private List<String> evaluatedFlags() {
    List<String> flags = new ArrayList<>();
    for (EvpProxy.Request request : evpProxy(app()).requests(FLAG_EVALUATION_PATH)) {
      flags.addAll(evaluatedFlags(request));
    }
    return flags;
  }

  private static List<String> evaluatedFlags(EvpProxy.Request request) {
    List<String> flags = new ArrayList<>();
    for (Object evaluation : (List<?>) request.json().get("flagEvaluations")) {
      Map<?, ?> flag = (Map<?, ?>) ((Map<?, ?>) evaluation).get("flag");
      flags.add((String) flag.get("key"));
    }
    return flags;
  }
}
