package datadog.trace.api.internal;

/**
 * Tracer internal features. Those features are not part of public API and can change or be removed
 * at any time.
 */
public interface InternalTracer {
  /**
   * Attach callbacks to the global scope manager.
   *
   * @param afterScopeActivatedCallback Callback on scope activation.
   * @param afterScopeClosedCallback Callback on scope close.
   */
  void addScopeListener(Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback);

  void flush();

  void flushMetrics();
}
