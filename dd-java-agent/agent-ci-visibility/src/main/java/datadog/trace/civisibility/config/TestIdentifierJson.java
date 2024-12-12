package datadog.trace.civisibility.config;

import com.squareup.moshi.Json;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;

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
}
