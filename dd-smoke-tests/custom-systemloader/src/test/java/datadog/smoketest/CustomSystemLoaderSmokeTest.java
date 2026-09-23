package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.test.util.Flaky;
import java.io.File;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CustomSystemLoaderSmokeTest {
  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("custom-systemloader")
          .jar(System.getProperty("datadog.smoketest.systemloader.shadowJar.path"))
          .jvmArgs("-Djava.system.class.loader=datadog.smoketest.systemloader.TestLoader")
          .workingDirectory(new File(System.getProperty("datadog.smoketest.builddir")))
          .debugLogs()
          .backend(testAgent())
          // The app may exit before sending telemetry.
          .skipTelemetryCheck()
          .build();

  @Test
  @DisplayName("resource types loaded by custom system class-loader are transformed")
  @Flaky(value = "Race condition with IBM. Check APMAPI-1194", condition = IbmJvm.class)
  void resourceTypesLoadedByCustomSystemClassLoaderAreTransformed() {
    app.assertCompletesWithValue(30, SECONDS, 0);
    // Wait for captured output to reach the marker printed after the resource classes are loaded.
    assertTrue(app.waitForLogLine("FIN"::equals));

    List<String> logLines = app.logLines();
    Predicate<String> loadedResource =
        compile("Loading sample.app.Resource[$]Test[1-3] from TestLoader").asPredicate();
    Predicate<String> transformedResource =
        compile(
                "Transformed.*class=sample.app.Resource[$]Test[1-3].*classloader=datadog.smoketest.systemloader.TestLoader")
            .asPredicate();
    assertEquals(3, logLines.stream().filter(loadedResource).count());
    assertEquals(3, logLines.stream().filter(transformedResource).count());
  }

  static class IbmJvm implements Predicate<String> {
    @Override
    public boolean test(String suite) {
      return JavaVirtualMachine.isIbm();
    }
  }
}
