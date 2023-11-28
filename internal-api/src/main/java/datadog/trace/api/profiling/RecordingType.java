package datadog.trace.api.profiling;

public enum RecordingType {
  CONTINUOUS("continuous");

  private final String name;

  RecordingType(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
