package datadog.trace.civisibility.config;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.config.api.dto.Data;
import datadog.trace.civisibility.config.api.dto.MultiEnvelope;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import datadog.trace.civisibility.config.api.dto.response.TestIdentifierJson;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
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

  /**
   * Builds a {@code SkippableTests} from the backend response envelope (or its file-based
   * equivalent), grouping identifiers by module and pulling correlation id and coverage from the
   * envelope meta.
   */
  public static SkippableTests from(
      MultiEnvelope<TestIdentifierJson> envelope, TracerEnvironment tracerEnvironment) {
    Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule = new HashMap<>();
    for (Data<TestIdentifierJson> entry : envelope.data) {
      TestIdentifierJson identifier = entry.attributes;
      if (identifier == null) {
        continue;
      }
      identifiersByModule
          .computeIfAbsent(identifier.resolveModuleName(tracerEnvironment), k -> new HashMap<>())
          .put(identifier.toTestIdentifier(), identifier.toTestMetadata());
    }

    String correlationId = envelope.meta != null ? envelope.meta.correlationId : null;
    Map<String, BitSet> coverage =
        envelope.meta != null && envelope.meta.coverage != null
            ? envelope.meta.coverage
            : Collections.emptyMap();
    return new SkippableTests(correlationId, identifiersByModule, coverage);
  }
}
