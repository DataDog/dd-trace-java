package datadog.trace.civisibility.config.api.dto;

public final class Envelope<T> {
  public final Data<T> data;

  public Envelope(Data<T> data) {
    this.data = data;
  }
}
