package datadog.smoketest.concurrent;

import static datadog.smoketest.backend.AgentBackend.mockAgent;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.SmokeCliApp;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractStructuredConcurrencyTest {
  @RegisterExtension
  final SmokeCliApp app =
      SmokeCliApp.named(testCaseName())
          .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs("--enable-preview", "-Ddd.trace.otel.enabled=true")
          .args(testCaseName())
          .backend(mockAgent())
          .build();

  protected abstract String testCaseName();

  protected abstract Predicate<DecodedTrace> checkTrace();

  protected void receivedCorrectTrace() {
    app.assertCompletesWithValue(10, SECONDS, 0);
    app.traces().waitForTraceCount(1);
    List<DecodedTrace> traces = app.traces().getTraces();
    assertEquals(1, traces.size());
    assertTrue(checkTrace().test(traces.get(0)), "Unexpected structured concurrency trace");
  }

  protected Optional<DecodedSpan> findRootSpan(DecodedTrace trace, String resource) {
    return trace.getSpans().stream()
        .filter(span -> Objects.equals(resource, span.getResource()) && span.getParentId() == 0)
        .findFirst();
  }

  protected Optional<DecodedSpan> findChildSpan(
      DecodedTrace trace, String resource, long parentSpanId) {
    return trace.getSpans().stream()
        .filter(span ->
            Objects.equals(resource, span.getResource()) && span.getParentId() == parentSpanId)
        .findFirst();
  }

  protected boolean hasChildSpan(DecodedTrace trace, String resource, long parentSpanId) {
    return findChildSpan(trace, resource, parentSpanId).isPresent();
  }
}
