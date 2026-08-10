package datadog.trace.civisibility.config.api.dto.response;

import com.squareup.moshi.Json;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.civisibility.config.api.dto.Data;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class TestIdentifierJson {

  private final String suite;
  private final String name;
  private final String parameters;
  private final Configurations configurations;

  @Json(name = "_missing_line_code_coverage")
  private final boolean missingLineCodeCoverage;

  public TestIdentifierJson(
      String suite,
      String name,
      String parameters,
      Configurations configurations,
      boolean missingLineCodeCoverage) {
    this.suite = suite;
    this.name = name;
    this.parameters = parameters;
    this.configurations = configurations;
    this.missingLineCodeCoverage = missingLineCodeCoverage;
  }

  public Configurations getConfigurations() {
    return configurations;
  }

  public boolean isMissingLineCodeCoverage() {
    return missingLineCodeCoverage;
  }

  public String getName() {
    return name;
  }

  public String getParameters() {
    return parameters;
  }

  public String getSuite() {
    return suite;
  }

  public TestIdentifier toTestIdentifier() {
    return new TestIdentifier(suite, name, parameters);
  }

  public TestMetadata toTestMetadata() {
    return new TestMetadata(missingLineCodeCoverage);
  }

  /**
   * Returns the module (test bundle) this identifier belongs to, preferring its own configurations
   * when they specify a test bundle and otherwise falling back to the request-level configurations.
   */
  public String resolveModuleName(TracerEnvironment tracerEnvironment) {
    Configurations requestConf = tracerEnvironment.getConfigurations();
    return (configurations != null && configurations.getTestBundle() != null
            ? configurations
            : requestConf)
        .getTestBundle();
  }

  /** Groups the given test identifiers into a {@code module -> Set<TestFQN>} map. */
  public static Map<String, Collection<TestFQN>> toTestFQNsByModule(
      Collection<Data<TestIdentifierJson>> data, TracerEnvironment tracerEnvironment) {
    Map<String, Collection<TestFQN>> testsByModule = new HashMap<>();
    for (Data<TestIdentifierJson> entry : data) {
      TestIdentifierJson identifier = entry.attributes;
      if (identifier == null) {
        continue;
      }
      testsByModule
          .computeIfAbsent(identifier.resolveModuleName(tracerEnvironment), k -> new HashSet<>())
          .add(identifier.toTestIdentifier().toFQN());
    }
    return testsByModule;
  }
}
