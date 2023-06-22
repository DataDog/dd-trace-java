package datadog.trace.api.civisibility.config;

import java.util.Objects;
import javax.annotation.Nullable;

public class SkippableTest {

  private final String suite;
  private final String name;
  private @Nullable final String parameters;
  private @Nullable final Configurations configurations;

  public SkippableTest(
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SkippableTest that = (SkippableTest) o;
    return Objects.equals(suite, that.suite)
        && Objects.equals(name, that.name)
        && Objects.equals(parameters, that.parameters)
        && Objects.equals(configurations, that.configurations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(suite, name, parameters, configurations);
  }

  @Override
  public String toString() {
    return "SkippableTest{"
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
