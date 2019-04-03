package datadog.opentracing.jfr;

/** Span event */
public interface DDSpanEvent {

  void start();

  void finish();
}
