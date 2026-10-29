package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.PollingConditions;
import groovy.json.JsonSlurper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies SCA reachability inside a Lambda handler with AppSec enabled and disabled. The disabled
 * configuration also verifies that SCA initializes independently of AppSec.
 */
class LambdaScaSmokeTest {
  @RegisterExtension static final SmokeServerApp scaOnlyApp = createApp(false);

  @RegisterExtension static final SmokeServerApp appsecApp = createApp(true);

  private static SmokeServerApp createApp(boolean appsecEnabled) {
    return SmokeServerApp.named(appsecEnabled ? "lambda-sca-appsec" : "lambda-sca-only")
        .mainClass(
            "datadog.smoketest.lambda.LambdaScaApplication",
            System.getProperty("datadog.smoketest.lambda.classpath"))
        .args("${app.httpPort}")
        .env("AWS_LAMBDA_FUNCTION_NAME", "sca-smoke")
        .env("_HANDLER", "datadog.smoketest.lambda.LambdaScaHandler::handleRequest")
        .jvmArgs("-Ddd.appsec.sca.enabled=true", "-Ddd.appsec.enabled=" + appsecEnabled)
        .backend(testAgent())
        .build();
  }

  @Test
  void reportsAVulnerableCallInsideTheLambdaHandlerWithScaAlone() {
    assertReachability(scaOnlyApp);
  }

  @Test
  void reportsAVulnerableCallInsideTheLambdaHandlerWithAppSecEnabled() {
    assertReachability(appsecApp);
  }

  private static void assertReachability(SmokeServerApp app) {
    assertEquals(200, app.get("/load-dependency"));
    // Advisory telemetry is emitted after deferred retransformation has completed. No watched
    // method has executed yet, so this establishes readiness without a sleep or a warm-up call.
    app.backend()
        .telemetry()
        .waitForFlat(
            event ->
                advisories(event).stream()
                    .anyMatch(advisory -> Collections.emptyList().equals(advisory.get("reached"))));

    assertEquals(200, app.get("/invoke/warm"));
    new PollingConditions(30)
        .eventually(
            () ->
                assertTrue(
                    app.backend().traces().getTraces().stream()
                        .flatMap(trace -> trace.getSpans().stream())
                        .anyMatch(
                            span ->
                                "dd-tracer-serverless-span".equals(span.getName())
                                    && "serverless".equals(span.getType())
                                    && "warm".equals(span.getMeta().get("request_id"))),
                    "expected serverless invocation span for request warm"));
    app.backend()
        .telemetry()
        .waitForFlat(
            event ->
                reachedCallsites(event).stream()
                    .anyMatch(hit -> "warmInvocation".equals(hit.get("symbol"))));

    Map<String, Object> caller =
        app.backend().telemetry().getFlatMessages().stream()
            .flatMap(event -> reachedCallsites(event).stream())
            .filter(hit -> "warmInvocation".equals(hit.get("symbol")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected Lambda caller was not reported"));
    assertEquals("datadog.smoketest.lambda.LambdaScaHandler", caller.get("path"));
    assertEquals("warmInvocation", caller.get("symbol"));
    assertTrue(caller.get("line") instanceof Number, "caller line must be numeric");
    assertEquals(38, ((Number) caller.get("line")).intValue());
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> reachedCallsites(Map<String, Object> event) {
    List<Map<String, Object>> hits = new ArrayList<>();
    for (Map<String, Object> advisory : advisories(event)) {
      hits.addAll((List<Map<String, Object>>) advisory.get("reached"));
    }
    return hits;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> advisories(Map<String, Object> event) {
    List<Map<String, Object>> values = new ArrayList<>();
    if (!"app-dependencies-loaded".equals(event.get("request_type"))) return values;
    Map<String, Object> payload = (Map<String, Object>) event.get("payload");
    for (Map<String, Object> dependency : (List<Map<String, Object>>) payload.get("dependencies")) {
      if (!"junrar".equals(dependency.get("name")) || !"7.5.5".equals(dependency.get("version")))
        continue;
      Object metadata = dependency.get("metadata");
      if (!(metadata instanceof List)) continue;
      for (Map<String, Object> entry : (List<Map<String, Object>>) metadata) {
        if (!"reachability".equals(entry.get("type"))) continue;
        Map<String, Object> value =
            (Map<String, Object>) new JsonSlurper().parseText((String) entry.get("value"));
        if ("GHSA-hf5p-q87m-crj7".equals(value.get("id"))) values.add(value);
      }
    }
    return values;
  }
}
