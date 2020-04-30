package datadog.opentracing;

import datadog.trace.core.propagation.ExtractedContext;
import java.util.Objects;

class OTExtractedContext extends OTTagContext {
  private final ExtractedContext extractedContext;

  OTExtractedContext(final ExtractedContext delegate) {
    super(delegate);
    this.extractedContext = delegate;
  }

  @Override
  public String toTraceId() {
    return extractedContext.getTraceId().toString();
  }

  @Override
  public String toSpanId() {
    return extractedContext.getSpanId().toString();
  }

  ExtractedContext getDelegate() {
    return extractedContext;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final OTExtractedContext that = (OTExtractedContext) o;
    return extractedContext.equals(that.extractedContext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(extractedContext);
  }
}
