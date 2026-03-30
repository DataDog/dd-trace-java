package datadog.trace.api;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum ProtocolVersion {
  V0_4("0.4", "v0.4/traces", "v0.3/traces"),
  V0_5("0.5", "v0.5/traces", "v0.4/traces", "v0.3/traces"),
  V1_0("1.0", "v1.0/traces", "v0.4/traces", "v0.3/traces");

  private final String configValue;
  private final String endpoint;
  private final List<String> fallback;
  private final List<String> endpointsToProbe;

  ProtocolVersion(String configValue, String endpoint, String... fallback) {
    this.configValue = configValue;
    this.endpoint = endpoint;
    this.fallback = unmodifiableList(asList(fallback));

    List<String> endpoints = new ArrayList<>();
    endpoints.add(endpoint);
    endpoints.addAll(asList(fallback));
    this.endpointsToProbe = unmodifiableList(endpoints);
  }

  public String asConfigValue() {
    return configValue;
  }

  public String endpoint() {
    return endpoint;
  }

  public List<String> fallback() {
    return fallback;
  }

  public List<String> endpointsToProbe() {
    return endpointsToProbe;
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
    if (normalized.endsWith(V1_0.endpoint)) {
      return V1_0;
    }

    if (normalized.endsWith(V0_5.endpoint)) {
      return V0_5;
    }

    return V0_4;
  }
}
