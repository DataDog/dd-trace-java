package datadog.smoketest;

import static datadog.smoketest.backend.TraceBackend.testAgent;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class OpenTelemetrySmokeTest {
  private static final int TIMEOUT_SECONDS = 30;
  private static final File WORKING_DIRECTORY =
      new File(System.getProperty("datadog.smoketest.builddir"));
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");

  @RegisterExtension
  static final SmokeApp app =
      SmokeApp.named("opentelemetry")
          .jar(APPLICATION_JAR)
          .jvmArgs("-Ddd.trace.otel.enabled=true")
          .workingDirectory(WORKING_DIRECTORY)
          .notAServer()
          .backend(testAgent())
          .build();

  @Test
  void receivesTraces() {
    app.traces()
        .assertTraces(
            trace(span().root().operationName("Application.annotatedSpan")),
            trace(span().root().resourceName("span-0")),
            trace(span().root().resourceName("span-1")),
            trace(span().root().resourceName("span-2")),
            trace(span().root().resourceName("span-3")),
            trace(span().root().resourceName("span-4")),
            trace(span().root().resourceName("span-5")),
            trace(span().root().resourceName("span-6")),
            trace(span().root().resourceName("span-7")),
            trace(span().root().resourceName("span-8")),
            trace(span().root().resourceName("span-9")));
    app.assertCompletesWithValue(TIMEOUT_SECONDS, SECONDS, 0);
  }
}
