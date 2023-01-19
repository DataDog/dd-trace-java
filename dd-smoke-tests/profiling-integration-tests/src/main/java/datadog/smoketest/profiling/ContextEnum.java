package datadog.smoketest.profiling;

public enum ContextEnum {
  FOO,
  BAR;

  @Override
  public String toString() {
    return name().toLowerCase();
  }
}
