package datadog.trace.api.civisibility;

/**
 * Handle to a CI Visibility event (test session or test module) that lets users attach custom tags
 * from a test framework lifecycle callback. Obtain instances via {@link
 * CIVisibility#activeTestSessions()} or {@link CIVisibility#activeTestModules()}.
 */
public interface CIVisibilityEvent {

  void setTag(String key, Object value);
}
