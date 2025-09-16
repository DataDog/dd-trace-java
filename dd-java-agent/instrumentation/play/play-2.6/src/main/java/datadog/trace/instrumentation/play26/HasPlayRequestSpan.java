package datadog.trace.instrumentation.play26;

import play.api.libs.typedmap.TypedKey;

public final class HasPlayRequestSpan {
  public static final TypedKey<HasPlayRequestSpan> KEY = TypedKey.apply("HasPlayRequest");
  public static final HasPlayRequestSpan INSTANCE = new HasPlayRequestSpan();

  private HasPlayRequestSpan() {}
}
