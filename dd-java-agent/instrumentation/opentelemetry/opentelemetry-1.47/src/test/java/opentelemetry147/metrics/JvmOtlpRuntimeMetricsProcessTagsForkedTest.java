package opentelemetry147.metrics;

import static opentelemetry147.metrics.JvmOtlpRuntimeMetricsTest.forEachJvmPoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.agent.jmxfetch.JvmOtlpRuntimeMetrics;
import datadog.trace.api.ProcessTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Forked because ProcessTags and JvmOtlpRuntimeMetrics cache process-wide state.
class JvmOtlpRuntimeMetricsProcessTagsForkedTest {

  @BeforeAll
  static void setUp() {
    System.setProperty("dd.metrics.otel.enabled", "true");
    ProcessTags.addTag("custom.process.tag", "present-at-start");
    ProcessTags.addTag("instance", "custom-instance");
    ProcessTags.addTag("name", "custom-name");
    JvmOtlpRuntimeMetrics.start(true);
  }

  @Test
  void processTagsPresentAtStartAreReported() {
    forEachJvmPoint(
        (metric, point) ->
            assertEquals(
                "present-at-start",
                point.attrs.get("custom.process.tag"),
                metric + " " + point.attrs));
  }

  @Test
  void processTagsDoNotReplaceJmxFetchAttributes() {
    forEachJvmPoint(
        (metric, point) -> {
          String context = metric + " " + point.attrs;
          assertEquals("dd-java-agent default", point.attrs.get("instance"), context);
          // Threading, ClassLoading, OperatingSystem and Memory points have no "name" property
          assertNotEquals("custom-name", point.attrs.get("name"), context);
        });
  }
}
