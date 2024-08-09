package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class SkippableTests {

  private final String correlationId;
  private final List<TestIdentifier> identifiers;
  private final Map<String, BitSet> coveredLinesByRelativeSourcePath;

  public SkippableTests(
      @Nullable String correlationId,
      List<TestIdentifier> identifiers,
      @Nullable Map<String, BitSet> coveredLinesByRelativeSourcePath) {
    this.correlationId = correlationId;
    this.identifiers = identifiers;
    this.coveredLinesByRelativeSourcePath = coveredLinesByRelativeSourcePath;
  }

  @Nullable
  public String getCorrelationId() {
    return correlationId;
  }

  public List<TestIdentifier> getIdentifiers() {
    return identifiers;
  }

  @Nullable
  public Map<String, BitSet> getCoveredLinesByRelativeSourcePath() {
    return coveredLinesByRelativeSourcePath;
  }
}
