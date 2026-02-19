package datadog.trace.api.civisibility.config;

import java.util.Objects;
import datadog.trace.util.HashingUtils;

/**
 * Fully Qualified Name: uniquely identifies a test case within a module by name. Multiple
 * executions of the same test case (for example when retries are done) will have the same FQN.
 */
public class TestFQN {
  private final String suite;
  private final String name;

  public TestFQN(String suite, String name) {
    this.suite = suite;
    this.name = name;
  }

  public String getSuite() {
    return suite;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestFQN that = (TestFQN) o;
    return Objects.equals(suite, that.suite) && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(suite, name);
  }

  @Override
  public String toString() {
    return "TestFQN{" + "suite='" + suite + '\'' + ", name='" + name + '\'' + '}';
  }
}
