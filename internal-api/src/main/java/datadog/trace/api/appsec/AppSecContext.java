package datadog.trace.api.appsec;

/** Minimal view of the AppSec request context accessible across module boundaries. */
public interface AppSecContext {
  boolean isManuallyKept();
}
