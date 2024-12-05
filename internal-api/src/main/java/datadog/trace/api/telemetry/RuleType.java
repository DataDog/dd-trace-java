package datadog.trace.api.telemetry;

public enum RuleType {
  LFI("lfi"),
  SQL_INJECTION("sql_injection"),
  SSRF("ssrf");

  public final String name;
  private static final int numValues = RuleType.values().length;

  RuleType(String name) {
    this.name = name;
  }

  public static int getNumValues() {
    return numValues;
  }

  @Override
  public String toString() {
    return name;
  }
}
