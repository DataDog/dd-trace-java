package datadog.trace.api;

import java.util.Locale;

public enum ProtocolVersion {
  V0_4("0.4", new String[] {"v0.4/traces", "v0.3/traces"}),
  V0_5("0.5", new String[] {"v0.5/traces", "v0.4/traces", "v0.3/traces"}),
  V1_0("1.0", new String[] {"v1.0/traces", "v0.5/traces", "v0.4/traces", "v0.3/traces"});

  private final String configValue;
  private final String[] traceEndpoints;

  ProtocolVersion(String configValue, String[] traceEndpoints) {
    this.configValue = configValue;
    this.traceEndpoints = traceEndpoints;
  }

  public String asConfigValue() {
    return configValue;
  }

  public String[] traceEndpoints() {
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
    if (normalized.endsWith(V1_0.traceEndpoints[0])) {
      return V1_0;
    }

    if (normalized.endsWith(V0_5.traceEndpoints[0])) {
      return V0_5;
    }

    return V0_4;
  }
}
