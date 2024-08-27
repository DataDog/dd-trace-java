package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import java.util.BitSet;
import java.util.Map;
import javax.annotation.Nullable;

public class SkippableTests {

  private final String correlationId;
  private final Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule;
  private final Map<String, BitSet> coveredLinesByRelativeSourcePath;

  public SkippableTests(
      @Nullable String correlationId,
      Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule,
      @Nullable Map<String, BitSet> coveredLinesByRelativeSourcePath) {
    this.correlationId = correlationId;
    this.identifiersByModule = identifiersByModule;
    this.coveredLinesByRelativeSourcePath = coveredLinesByRelativeSourcePath;
  }

  @Nullable
  public String getCorrelationId() {
    return correlationId;
  }

  public Map<String, Map<TestIdentifier, TestMetadata>> getIdentifiersByModule() {
    return identifiersByModule;
  }

  @Nullable
  public Map<String, BitSet> getCoveredLinesByRelativeSourcePath() {
    return coveredLinesByRelativeSourcePath;
  }
}
