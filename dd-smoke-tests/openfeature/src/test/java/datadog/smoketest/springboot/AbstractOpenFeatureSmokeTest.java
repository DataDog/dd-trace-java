package datadog.smoketest.springboot;

import static datadog.smoketest.springboot.OpenFeatureFixtures.UFC_CONFIG;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evaluate;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evaluationCases;
import static datadog.smoketest.springboot.OpenFeatureFixtures.evpProxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.smoketest.SmokeServerApp;
import datadog.smoketest.backend.EvpProxy;
import datadog.smoketest.springboot.OpenFeatureFixtures.Evaluation;
import datadog.smoketest.springboot.OpenFeatureFixtures.EvaluationCase;
import datadog.trace.test.util.PollingConditions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Evaluates the system tests cases through the application, and checks the exposures it reports.
 * The exposure cache reports each exposure once, so the exposures are checked before any other
 * evaluation.
 */
@TestMethodOrder(OrderAnnotation.class)
abstract class AbstractOpenFeatureSmokeTest {
  private static final Set<String> LOGGED_ALLOCATIONS = loggedAllocations();

  /**
   * Gets the application under test.
   *
   * @return The application under test.
   */
  abstract SmokeServerApp app();

  /**
   * Tells whether exposures are delivered through the Datadog Agent event platform proxy.
   *
   * @return Whether exposures are delivered through the Datadog Agent event platform proxy.
   */
  abstract boolean deliversExposures();

  /** Publishes the flag configuration before each evaluation. */
  void publishConfiguration() {}

  @Test
  @Order(1)
  void reportsExposures() throws IOException {
    assumeTrue(deliversExposures(), "exposures are not delivered");
    publishConfiguration();
    Set<Exposure> expected = new LinkedHashSet<>();
    for (EvaluationCase evaluationCase : evaluationCases()) {
      Evaluation evaluation = evaluate(app(), evaluationCase.request);
      assertEquals(200, evaluation.code, evaluation.body);
      Exposure exposure = expectedExposure(evaluationCase, evaluation.json());
      if (exposure != null) {
        expected.add(exposure);
      }
    }
    assertFalse(expected.isEmpty(), "some evaluations are exposed");

    EvpProxy evpProxy = evpProxy(app());
    new PollingConditions(10)
        .eventually(
            () -> {
              List<Exposure> reported = reportedExposures(evpProxy);
              assertEquals(expected.size(), reported.size(), "reported exposures: " + reported);
              for (Exposure exposure : expected) {
                assertTrue(reported.contains(exposure), "Unable to find exposure " + exposure);
              }
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("datadog.smoketest.springboot.OpenFeatureFixtures#evaluationCases")
  @Order(2)
  void evaluatesFlags(EvaluationCase evaluationCase) throws IOException {
    publishConfiguration();

    Evaluation evaluation = evaluate(app(), evaluationCase.request);

    assertEquals(200, evaluation.code, evaluation.body);
    Map<String, Object> actual = evaluation.json();
    Map<String, Object> expected = evaluationCase.result();
    assertTrue(
        Json.equal(expected.get("value"), actual.get("value")),
        "value: expected " + expected.get("value") + " but was " + actual.get("value"));
    assertEquals(expected.get("reason"), actual.get("reason"), "reason");
    if (expected.containsKey("errorCode")) {
      assertEquals(expected.get("errorCode"), actual.get("errorCode"), "error code");
    }
    if (expected.containsKey("variant")) {
      assertEquals(expected.get("variant"), actual.get("variant"), "variant");
    }
    Object allocationKey = allocationKey(expected);
    if (allocationKey != null && !"".equals(allocationKey)) {
      assertEquals(allocationKey, allocationKey(actual), "allocation key");
    }
  }

  private static Exposure expectedExposure(
      EvaluationCase evaluationCase, Map<String, Object> response) {
    String flag = evaluationCase.flag();
    Object allocation = allocationKey(response);
    Object variant = response.get("variant");
    if (variant == null
        || "".equals(variant)
        || allocation == null
        || "".equals(allocation)
        || !LOGGED_ALLOCATIONS.contains(flag + '\u0000' + allocation)) {
      return null;
    }
    return new Exposure(flag, allocation, variant, evaluationCase.targetingKey());
  }

  private static List<Exposure> reportedExposures(EvpProxy evpProxy) {
    List<Exposure> exposures = new ArrayList<>();
    for (EvpProxy.Request request : evpProxy.requests("api/v2/exposures")) {
      for (Object event : (List<?>) request.json().get("exposures")) {
        Map<String, Object> exposure = Json.object(event);
        exposures.add(
            new Exposure(
                key(exposure.get("flag")),
                key(exposure.get("allocation")),
                key(exposure.get("variant")),
                Json.object(exposure.get("subject")).get("id")));
      }
    }
    return exposures;
  }

  private static Object key(Object reference) {
    return Json.object(reference).get("key");
  }

  private static Object allocationKey(Map<String, Object> evaluation) {
    Object metadata = evaluation.get("flagMetadata");
    return metadata == null ? null : Json.object(metadata).get("allocationKey");
  }

  private static Set<String> loggedAllocations() {
    Set<String> logged = new HashSet<>();
    Map<String, Object> flags = Json.object(Json.object(Json.parse(UFC_CONFIG)).get("flags"));
    for (Map.Entry<String, Object> flag : flags.entrySet()) {
      Object allocations = Json.object(flag.getValue()).get("allocations");
      if (allocations instanceof List) {
        for (Object allocation : (List<?>) allocations) {
          if (allocation instanceof Map
              && Boolean.TRUE.equals(Json.object(allocation).get("doLog"))) {
            logged.add(flag.getKey() + '\u0000' + Json.object(allocation).get("key"));
          }
        }
      }
    }
    return logged;
  }

  /** An exposure, identified by its flag, allocation, variant and subject. */
  private static final class Exposure {
    private final Object flag;
    private final Object allocation;
    private final Object variant;
    private final Object subject;

    Exposure(Object flag, Object allocation, Object variant, Object subject) {
      this.flag = flag;
      this.allocation = allocation;
      this.variant = variant;
      this.subject = subject;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Exposure)) {
        return false;
      }
      Exposure that = (Exposure) other;
      return Objects.equals(this.flag, that.flag)
          && Objects.equals(this.allocation, that.allocation)
          && Objects.equals(this.variant, that.variant)
          && Objects.equals(this.subject, that.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.flag, this.allocation, this.variant, this.subject);
    }

    @Override
    public String toString() {
      return "flag="
          + this.flag
          + " allocation="
          + this.allocation
          + " variant="
          + this.variant
          + " subject="
          + this.subject;
    }
  }
}
