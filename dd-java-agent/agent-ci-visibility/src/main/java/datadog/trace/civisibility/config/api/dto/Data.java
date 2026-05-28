package datadog.trace.civisibility.config.api.dto;

public final class Data<T> {
  public final String id;
  public final String type;
  public final T attributes;

  public Data(String id, String type, T attributes) {
    this.id = id;
    this.type = type;
    this.attributes = attributes;
  }
}
