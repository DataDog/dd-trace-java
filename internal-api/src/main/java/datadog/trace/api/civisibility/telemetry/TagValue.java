package datadog.trace.api.civisibility.telemetry;

public interface TagValue {

  String asString();

  /** see {@link Enum#getDeclaringClass()} */
  Class<? extends TagValue> getDeclaringClass();

  /** see {@link Enum#ordinal()} */
  int ordinal();
}
