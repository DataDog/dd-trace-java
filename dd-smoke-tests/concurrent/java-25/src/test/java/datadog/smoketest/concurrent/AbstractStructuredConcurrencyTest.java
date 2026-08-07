package datadog.smoketest.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.AbstractJavaSmokeTest;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

abstract class AbstractStructuredConcurrencyTest extends AbstractJavaSmokeTest {
  private static final int TIMEOUT_SECONDS = 10;

  protected abstract String testCaseName();

  protected abstract Predicate<DecodedTrace> checkTrace();

  @Override
  protected ProcessBuilder createProcessBuilder() {
    String applicationJar = System.getProperty("datadog.smoketest.shadowJar.path");
    Path javaExecutable = Path.of(System.getenv("JAVA_HOME"), "bin", "java");

    var processBuilder = new ProcessBuilder(javaExecutable.toString());
    var command = processBuilder.command();
    command.addAll(defaultJavaProperties);
    command.addAll(
        List.of(
            "--enable-preview",
            "-Ddd.trace.otel.enabled=true",
            "-jar",
            applicationJar,
            testCaseName()));

    processBuilder.directory(buildDirectory.toFile());
    return processBuilder;
  }

  protected void receivedCorrectTrace() throws Exception {
    Process process = testedProcess;
    assertTrue(
        process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "Instrumented process did not exit within " + TIMEOUT_SECONDS + " seconds");
    assertEquals(0, process.exitValue(), "Instrumented process exited abnormally");
    waitForTrace(checkTrace());
    assertEquals(1, traceCount.get());
  }

  protected Optional<DecodedSpan> findRootSpan(DecodedTrace trace, String resource) {
    return trace.getSpans().stream()
        .filter(span -> Objects.equals(resource, span.getResource()) && span.getParentId() == 0)
        .findFirst();
  }

  protected Optional<DecodedSpan> findChildSpan(
      DecodedTrace trace, String resource, long parentSpanId) {
    return trace.getSpans().stream()
        .filter(
            span ->
                Objects.equals(resource, span.getResource()) && span.getParentId() == parentSpanId)
        .findFirst();
  }

  protected boolean hasChildSpan(DecodedTrace trace, String resource, long parentSpanId) {
    return findChildSpan(trace, resource, parentSpanId).isPresent();
  }
}
