package datadog.smoketest.spark;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.smoketest.SmokeCliApp;
import datadog.smoketest.backend.AgentBackend;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedSpanLink;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import org.apache.spark.launcher.JavaModuleOptions;
import org.junit.jupiter.api.Test;

abstract class AbstractSparkSpanLinksSmokeTest {
  private static final long EXTERNAL_TRACE_ID = Long.parseUnsignedLong("13959090542865903119");
  private static final long EXTERNAL_SPAN_ID = 2903780135964948649L;

  abstract SmokeCliApp app();

  abstract boolean spanLinksEnabled();

  @Test
  void serializesExpectedApplicationTopology() {
    app().traces().waitForTraceCount(1);
    app().assertCompletesWithValue(60, SECONDS, 0);

    List<DecodedTrace> traces = app().traces().getTraces();
    List<DecodedSpan> spans =
        traces.stream().flatMap(trace -> trace.getSpans().stream()).collect(Collectors.toList());
    DecodedSpan application = onlySpan(spans, "spark.application");
    DecodedSpan sql = onlySpan(spans, "spark.sql");
    DecodedSpan job = onlySpan(spans, "spark.job");
    List<DecodedSpan> stages = spans(spans, "spark.stage");
    List<DecodedSpan> topologySpans =
        spans.stream()
            .filter(span ->
                span.getName().equals("spark.application")
                    || span.getName().equals("spark.sql")
                    || span.getName().equals("spark.job")
                    || span.getName().equals("spark.stage"))
            .collect(Collectors.toList());

    assertFalse(stages.isEmpty());
    assertEquals(application.getSpanId(), sql.getParentId());
    assertEquals(sql.getSpanId(), job.getParentId());
    assertTrue(stages.stream().allMatch(stage -> stage.getParentId() == job.getSpanId()));
    assertTrue(
        topologySpans.stream().allMatch(span -> span.getTraceId() == application.getTraceId()));

    if (spanLinksEnabled()) {
      assertEquals(0, application.getParentId());
      assertNotEquals(EXTERNAL_TRACE_ID, application.getTraceId());
      assertEquals(1, application.getLinks().size());
      DecodedSpanLink link = application.getLinks().get(0);
      assertEquals(EXTERNAL_TRACE_ID, link.getTraceId());
      assertEquals(EXTERNAL_SPAN_ID, link.getSpanId());
    } else {
      assertEquals(EXTERNAL_TRACE_ID, application.getTraceId());
      assertEquals(EXTERNAL_SPAN_ID, application.getParentId());
      assertTrue(application.getLinks().isEmpty());
    }
  }

  static SmokeCliApp newApp(String name, Boolean dedicated, Boolean experimental) {
    String applicationJar = System.getProperty("datadog.smoketest.shadowJar.path");
    AgentBackend backend = AgentBackend.testAgent();
    SmokeCliApp.Builder builder =
        SmokeCliApp.named(name)
            .mainClass(SparkSpanLinksSmokeApplication.class, applicationJar)
            .backend(backend)
            .env("SPARK_LOCAL_HOSTNAME", "localhost")
            .jvmArgs(
                "-Ddd.data.jobs.enabled=true",
                "-Ddd.integration.spark.enabled=true",
                "-Ddd.trace.partial.flush.enabled=false")
            .skipTelemetryCheck();
    if (JavaVirtualMachine.isJavaVersionAtLeast(16)) {
      // Spark's launcher normally supplies these options; SmokeCliApp starts the main class
      // directly.
      StringTokenizer sparkJvmOptions =
          new StringTokenizer(JavaModuleOptions.defaultModuleOptions());
      while (sparkJvmOptions.hasMoreTokens()) {
        builder.jvmArgs(sparkJvmOptions.nextToken());
      }
    }
    if (dedicated != null) {
      builder.jvmArgs("-Ddd.data.jobs.spark.span-links=" + dedicated);
    }
    if (experimental != null) {
      builder.jvmArgs("-Ddd.data.jobs.experimental_features.enabled=" + experimental);
    }
    return builder.build();
  }

  private static List<DecodedSpan> spans(List<DecodedSpan> spans, String operationName) {
    return spans.stream()
        .filter(span -> operationName.equals(span.getName()))
        .collect(Collectors.toList());
  }

  private static DecodedSpan onlySpan(List<DecodedSpan> spans, String operationName) {
    List<DecodedSpan> matches = spans(spans, operationName);
    assertEquals(
        1,
        matches.size(),
        "expected exactly one "
            + operationName
            + " span; received "
            + spans.stream()
                .map(span -> span.getName() + "[" + span.getResource() + "]")
                .collect(Collectors.joining(", ")));
    return matches.get(0);
  }
}
