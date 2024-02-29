package datadog.trace.api.civisibility.config;

import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Uniquely identifies a test case. Multiple executions of the same test case (for example when
 * retries are done) will have the same test identifier.
 */
public class TestIdentifier {

  private final String suite;
  private final String name;
  /**
   * Some API endpoints do not return parameters data. If this field is {@code null} then either
   * corresponding test case is not parameterized, or this identifier refers to <strong>all</strong>
   * parameter variations.
   */
  private @Nullable final String parameters;
  /**
   * Configurations field is intentionally excluded from hashCode/equals and serialization logic:
   * the backend does not return full configuration for a test case, but rather includes only those
   * parts that were not specified in the client request (for instance, when requesting tests
   * without specifying module name, each test in the response will have module name present in its
   * config section). Moreover, in some edge cases the backend may choose to return empty
   * configuration object instead of null. Therefore, reconstructing on the client side a
   * configuration block that fully corresponds to the one returned by the backend is non-trivial.
   */
  private @Nullable final Configurations configurations;

  public TestIdentifier(
      String suite,
      String name,
      @Nullable String parameters,
      @Nullable Configurations configurations) {
    this.suite = suite;
    this.name = name;
    this.parameters = parameters;
    this.configurations = configurations;
  }

  public String getSuite() {
    return suite;
  }

  public String getName() {
    return name;
  }

  @Nullable
  public String getParameters() {
    return parameters;
  }

  @Nullable
  public Configurations getConfigurations() {
    return configurations;
  }

  public TestIdentifier withoutParameters() {
    return parameters == null ? this : new TestIdentifier(suite, name, null, configurations);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestIdentifier that = (TestIdentifier) o;
    return Objects.equals(suite, that.suite)
        && Objects.equals(name, that.name)
        && Objects.equals(parameters, that.parameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(suite, name, parameters);
  }

  @Override
  public String toString() {
    return "TestIdentifier{"
        + "suite='"
        + suite
        + '\''
        + ", name='"
        + name
        + '\''
        + ", parameters='"
        + parameters
        + '\''
        + ", configurations="
        + configurations
        + '}';
  }
}
