package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.util.List;
import javax.annotation.Nullable;

public class SkippableTests {

  private final String correlationId;
  private final List<TestIdentifier> identifiers;

  public SkippableTests(String correlationId, List<TestIdentifier> identifiers) {
    this.correlationId = correlationId;
    this.identifiers = identifiers;
  }

  @Nullable
  public String getCorrelationId() {
    return correlationId;
  }

  public List<TestIdentifier> getIdentifiers() {
    return identifiers;
  }
}
