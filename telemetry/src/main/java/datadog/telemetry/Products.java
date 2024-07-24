package datadog.telemetry;

public enum Products {
  APPSEC("appsec"),
  PROFILER("profiler"),
  DYNAMIC_INSTRUMENTATION("dynamic_instrumentation");

  private final String name;

  Products(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
