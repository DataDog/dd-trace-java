package datadog.trace.api.civisibility.config;

import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Uniquely identifies a test case within a module. Multiple executions of the same test case (for
 * example when retries are done) will have the same test identifier.
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

  public TestIdentifier(String suite, String name, @Nullable String parameters) {
    this.suite = suite;
    this.name = name;
    this.parameters = parameters;
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

  public TestIdentifier withoutParameters() {
    return parameters == null ? this : new TestIdentifier(suite, name, null);
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
        + '}';
  }
}
