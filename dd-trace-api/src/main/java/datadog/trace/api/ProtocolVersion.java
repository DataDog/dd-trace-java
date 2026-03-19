package datadog.trace.api;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import java.util.List;
import java.util.Locale;

public enum ProtocolVersion {
  V0_4("0.4", asList("v0.4/traces", "v0.3/traces")),
  V0_5("0.5", asList("v0.5/traces", "v0.4/traces", "v0.3/traces")),
  V1_0("1.0", asList("v1.0/traces", "v0.4/traces", "v0.3/traces"));

  private final String configValue;
  private final List<String> traceEndpoints;

  ProtocolVersion(String configValue, List<String> traceEndpoints) {
    this.configValue = configValue;
    this.traceEndpoints = unmodifiableList(traceEndpoints);
  }

  public String asConfigValue() {
    return configValue;
  }

  public List<String> traceEndpoints() {
    return traceEndpoints;
  }

  public static ProtocolVersion fromConfigValue(String value) {
    if (value == null) {
      return V0_4;
    }

    String normalized = value.toLowerCase(Locale.ROOT);
    if (V1_0.configValue.equals(normalized)) {
      return V1_0;
    }

    if (V0_5.configValue.equals(normalized)) {
      return V0_5;
    }

    return V0_4;
  }

  public static ProtocolVersion fromTraceEndpoint(String endpoint) {
    if (endpoint == null) {
      return null;
    }

    String normalized = endpoint.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(V1_0.traceEndpoints.get(0))) {
      return V1_0;
    }

    if (normalized.endsWith(V0_5.traceEndpoints.get(0))) {
      return V0_5;
    }

    return V0_4;
  }
}
