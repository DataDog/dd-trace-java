package datadog.trace.tracer;

public class MutableSpanImpl extends AbstractSpan {

  MutableSpanImpl(final TraceInternal trace, final Span span) {
    super(trace, span);
  }

  @Override
  public void finish() {
    // TODO throw an exception?
  }

  @Override
  public void finish(final long finishTimestampNanoseconds) {
    // TODO throw an exception?
  }

}
