package datadog.communication.otlp;

import static datadog.communication.ddagent.TracerVersion.TRACER_VERSION;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtlpCanonicalResourceAttributesTest {

  @Test
  void visitsAllAttributesInOrder() {
    Config config = config("env", "1.0", true, "host");

    assertEquals(
        asList(
            "service.name=svc",
            "deployment.environment.name=env",
            "service.version=1.0",
            "host.name=host",
            "telemetry.sdk.name=datadog",
            "telemetry.sdk.version=" + TRACER_VERSION,
            "telemetry.sdk.language=java"),
        visit(config));
  }

  @Test
  void skipsEmptyAndUnreportedAttributes() {
    Config config = config("", "", false, "host");

    assertEquals(
        asList(
            "service.name=svc",
            "telemetry.sdk.name=datadog",
            "telemetry.sdk.version=" + TRACER_VERSION,
            "telemetry.sdk.language=java"),
        visit(config));
  }

  @Test
  void skipsEmptyHostName() {
    Config config = config("", "", true, "");

    assertEquals(4, visit(config).size());
  }

  private static Config config(String env, String version, boolean reportHost, String host) {
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("svc");
    when(config.getEnv()).thenReturn(env);
    when(config.getVersion()).thenReturn(version);
    when(config.isReportHostName()).thenReturn(reportHost);
    when(config.getHostName()).thenReturn(host);
    return config;
  }

  private static List<String> visit(Config config) {
    List<String> visited = new ArrayList<>();
    OtlpCanonicalResourceAttributes.visit(config, (k, v) -> visited.add(k + "=" + v));
    return visited;
  }
}
