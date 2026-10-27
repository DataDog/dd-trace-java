package datadog.trace.api.appsec;

/** Minimal view of the AppSec request context accessible across module boundaries. */
public interface AppSecContext {
  boolean isManuallyKept();

  /** Reports that an attempted AppSec block could not be committed or enforced. */
  void reportBlockFailure();
}
