package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.environment.JavaVirtualMachine;
import datadog.smoketest.backend.AgentBackend;
import datadog.trace.test.util.Flaky;
import java.io.File;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SampleTraceSmokeTest {
  private static final AgentBackend BACKEND = testAgent();

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("sample-trace")
          .jar(System.getProperty("datadog.smoketest.agent.shadowJar.path"))
          // tests tracer as a jar sending sample traces instead of a javaagent
          .noAgent()
          .backend(BACKEND)
          .placeholder("agent.host", () -> BACKEND.url().getHost())
          .placeholder("agent.port", () -> Integer.toString(BACKEND.port()))
          .jvmArgs(
              "-Ddd.agent.host=${agent.host}",
              "-Ddd.trace.agent.port=${agent.port}",
              "-Ddd.test.agent.session.token=" + BACKEND.sessionToken())
          .args("sampleTrace", "-c", "10", "-i", "0.1")
          .workingDirectory(new File(System.getProperty("datadog.smoketest.builddir")))
          .build();

  @Test
  @DisplayName("sample traces are sent")
  @Flaky(condition = IbmJvm.class)
  void sampleTracesAreSent() {
    app.traces().waitForTraceCount(10);
    app.assertCompletesWithValue(30, SECONDS, 0);
    assertEquals(10, app.traces().getTraces().size());
  }

  static class IbmJvm implements Predicate<String> {
    @Override
    public boolean test(String suite) {
      return JavaVirtualMachine.isIbm();
    }
  }
}
