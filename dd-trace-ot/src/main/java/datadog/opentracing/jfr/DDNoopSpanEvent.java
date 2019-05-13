package datadog.opentracing.jfr;

/** Span event implementation that does no reporting */
public final class DDNoopSpanEvent implements DDSpanEvent {
  @Override
  public void start() {
    // Noop
  }

  @Override
  public void finish() {
    // Noop
  }
}
