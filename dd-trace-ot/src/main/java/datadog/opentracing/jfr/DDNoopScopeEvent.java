package datadog.opentracing.jfr;

/** Scope event implementation that does no reporting */
public final class DDNoopScopeEvent implements DDScopeEvent {
  @Override
  public void start() {
    // Noop
  }

  @Override
  public void finish() {
    // Noop
  }
}
