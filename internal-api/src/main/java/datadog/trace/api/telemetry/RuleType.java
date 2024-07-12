package datadog.trace.api.telemetry;

public enum RuleType {
  LIF("lfi"),
  SQL_INJECTION("sql_injection"),
  SSRF("ssrf");

  public final String name;

  RuleType(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return name;
  }
}
