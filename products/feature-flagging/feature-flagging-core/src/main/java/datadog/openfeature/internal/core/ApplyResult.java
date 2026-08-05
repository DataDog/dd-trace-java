package datadog.openfeature.internal.core;

/** Result of applying configuration bytes to the provider-owned configuration state. */
public enum ApplyResult {
  ACCEPTED,
  REJECTED,
  CLEARED
}
