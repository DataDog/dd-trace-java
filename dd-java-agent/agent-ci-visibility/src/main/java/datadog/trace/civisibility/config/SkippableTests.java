package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import java.util.BitSet;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SkippableTests {

  public static final SkippableTests EMPTY =
      new SkippableTests(null, Collections.emptyMap(), Collections.emptyMap());

  private final String correlationId;
  private final Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule;
  private final Map<String, BitSet> coveredLinesByRelativeSourcePath;

  public SkippableTests(
      @Nullable String correlationId,
      @Nonnull Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule,
      @Nonnull Map<String, BitSet> coveredLinesByRelativeSourcePath) {
    this.correlationId = correlationId;
    this.identifiersByModule = identifiersByModule;
    this.coveredLinesByRelativeSourcePath = coveredLinesByRelativeSourcePath;
  }

  @Nullable
  public String getCorrelationId() {
    return correlationId;
  }

  @Nonnull
  public Map<String, Map<TestIdentifier, TestMetadata>> getIdentifiersByModule() {
    return identifiersByModule;
  }

  @Nonnull
  public Map<String, BitSet> getCoveredLinesByRelativeSourcePath() {
    return coveredLinesByRelativeSourcePath;
  }
}
