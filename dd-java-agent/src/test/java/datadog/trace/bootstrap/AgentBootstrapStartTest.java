package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import org.junit.jupiter.api.Test;

class AgentBootstrapStartTest {
  private static final RuntimeException START_FAILURE = new RuntimeException("start failed");
  private static final RuntimeException RELEASE_FAILURE = new RuntimeException("release failed");

  @Test
  void preservesStartFailureWhenReleaseAlsoFails() {
    Throwable failure =
        assertThrows(
            Throwable.class,
            () ->
                AgentBootstrap.invokeStartAndRelease(
                    FailingAgent.class, "datadog.trace.bootstrap.Agent", null, null, null, null));

    assertSame(START_FAILURE, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertSame(RELEASE_FAILURE, failure.getSuppressed()[0].getCause());
  }

  public static final class FailingAgent {
    public static void start(
        Object telemetry, Instrumentation instrumentation, URL agentJar, String agentArgs) {
      throw START_FAILURE;
    }

    public static void releaseClassData() {
      throw RELEASE_FAILURE;
    }
  }
}
