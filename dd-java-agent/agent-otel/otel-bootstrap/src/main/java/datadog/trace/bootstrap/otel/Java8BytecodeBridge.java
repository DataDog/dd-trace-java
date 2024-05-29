package datadog.trace.bootstrap.otel;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/** Support references to OpenTelemetry's Java8BytecodeBridge in external extensions. */
public final class Java8BytecodeBridge {

  // Static helpers that will redirect to our embedded copy of the OpenTelemetry API

  public static Context currentContext() {
    return Context.current();
  }

  public static Context rootContext() {
    return Context.root();
  }

  public static Span currentSpan() {
    return Span.current();
  }

  public static Span spanFromContext(Context context) {
    return Span.fromContext(context);
  }

  public static Baggage baggageFromContext(Context context) {
    return Baggage.fromContext(context);
  }

  private Java8BytecodeBridge() {}
}
