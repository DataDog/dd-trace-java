package datadog.trace.bootstrap.instrumentation.api;

public class Schema {
  public final String definition;
  public final String id;

  public Schema(String definition, String id) {
    this.definition = definition;
    this.id = id;
  }
}
