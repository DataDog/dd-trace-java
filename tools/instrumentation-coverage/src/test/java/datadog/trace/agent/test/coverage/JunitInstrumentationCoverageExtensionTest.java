package datadog.trace.agent.test.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import coveragefixture.Fixture;
import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JunitInstrumentationCoverageExtensionTest {
  @TempDir Path output;

  @Test
  void failedInvocationRetainsFailureAndStopsCollection() throws Exception {
    Fixture fixture = new Fixture();
    AssertionError original = new AssertionError("original assertion failure");
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignored = coverage) {
      coverage.pause();
      fixture.call(0); // setup is outside the invocation window
      assertSame(
          original,
          assertThrows(
              AssertionError.class,
              () ->
                  JunitInstrumentationCoverageExtension.runObserved(
                      coverage,
                      "failed-invocation",
                      () -> {
                        fixture.call(1);
                        throw original;
                      })));
      fixture.call(2); // teardown remains outside the window after failure
    }
    coverage.writeReport(output);
    assertEquals(Map.of("failed-invocation", 1L), counts());
    assertSame(Context.root(), Context.current());
  }

  @Test
  void invocationLabelsRemainDistinctAndContextIsPreserved() throws Throwable {
    Fixture fixture = new Fixture();
    Context request = Context.root().with(ContextKey.named("junit-test"), "request");
    ContextCoverage coverage = ContextCoverage.observe(Fixture.class);
    try (ContextCoverage ignored = coverage;
        ContextScope scope = request.attach()) {
      coverage.pause();
      for (String id :
          new String[] {"same display [invocation:1]", "same display [invocation:2]"}) {
        JunitInstrumentationCoverageExtension.runObserved(
            coverage,
            id,
            "same display",
            () -> {
              fixture.call(1);
              assertSame(request, Context.current());
              return null;
            });
      }
      assertSame(request, Context.current());
    }
    coverage.writeReport(output);
    assertEquals(Map.of("same display", 2L), counts());
    assertEquals(
        Map.of("same display [invocation:1]", 1L, "same display [invocation:2]", 1L),
        countsByScenarioId());
    assertSame(Context.root(), Context.current());
  }

  private Map<String, Long> counts() throws Exception {
    JsonObject report =
        JsonParser.parseString(Files.readString(output.resolve("report.json"))).getAsJsonObject();
    Map<String, Long> counts = new HashMap<>();
    for (JsonElement method : report.getAsJsonArray("methods")) {
      for (JsonElement value : method.getAsJsonObject().getAsJsonArray("observations")) {
        JsonObject observation = value.getAsJsonObject();
        counts.merge(
            observation.get("scenario").getAsString(),
            observation.get("count").getAsLong(),
            Long::sum);
      }
    }
    return counts;
  }

  private Map<String, Long> countsByScenarioId() throws Exception {
    JsonObject report =
        JsonParser.parseString(Files.readString(output.resolve("report.json"))).getAsJsonObject();
    Map<String, Long> counts = new HashMap<>();
    for (JsonElement method : report.getAsJsonArray("methods")) {
      for (JsonElement value : method.getAsJsonObject().getAsJsonArray("observations")) {
        JsonObject observation = value.getAsJsonObject();
        counts.merge(
            observation.get("scenarioId").getAsString(),
            observation.get("count").getAsLong(),
            Long::sum);
        assertEquals("EXACT", observation.get("attributionConfidence").getAsString());
      }
    }
    return counts;
  }
}
