package datadog.trace.api.civisibility.config;

import java.util.Objects;
import javax.annotation.Nullable;
import datadog.trace.util.HashingUtils;

/** Uniquely identifies a test case with FQN and parameters. */
public class TestIdentifier {
  private final TestFQN fqn;

  /**
   * Some API endpoints do not return parameters data. If this field is {@code null} then either
   * corresponding test case is not parameterized, or this identifier refers to <strong>all</strong>
   * parameter variations.
   */
  private @Nullable final String parameters;

  public TestIdentifier(String suite, String name, @Nullable String parameters) {
    this.fqn = new TestFQN(suite, name);
    this.parameters = parameters;
  }

  public TestIdentifier(TestFQN testFQN, @Nullable String parameters) {
    this.fqn = testFQN;
    this.parameters = parameters;
  }

  public String getSuite() {
    return fqn.getSuite();
  }

  public String getName() {
    return fqn.getName();
  }

  @Nullable
  public String getParameters() {
    return parameters;
  }

  public TestFQN toFQN() {
    return fqn;
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
    return Objects.equals(fqn, that.fqn) && Objects.equals(parameters, that.parameters);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(fqn, parameters);
  }

  @Override
  public String toString() {
    return "TestIdentifier{" + "fqn=" + fqn + ", parameters='" + parameters + '\'' + '}';
  }
}
