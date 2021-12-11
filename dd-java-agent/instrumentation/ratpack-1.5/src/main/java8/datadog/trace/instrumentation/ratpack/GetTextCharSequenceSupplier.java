package datadog.trace.instrumentation.ratpack;

import datadog.trace.api.function.Supplier;
import ratpack.http.TypedData;

public final class GetTextCharSequenceSupplier implements Supplier<CharSequence> {
  private final TypedData thiz;

  public GetTextCharSequenceSupplier(TypedData thiz) {
    this.thiz = thiz;
  }

  @Override
  public CharSequence get() {
    return thiz.getText();
  }
}
