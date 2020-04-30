package datadog.trace.core.jfr;

/** Scope event */
public interface DDScopeEvent {

  void start();

  void finish();
}
