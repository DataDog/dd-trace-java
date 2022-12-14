package datadog.trace.instrumentation.ratpack;

import java.util.function.Supplier;
import ratpack.http.TypedData;

public class GetTextCharSequenceSupplier implements Supplier<CharSequence> {
  private final TypedData thiz;

  public GetTextCharSequenceSupplier(TypedData thiz) {
    this.thiz = thiz;
  }

  @Override
  public CharSequence get() {
    return thiz.getText();
  }
}
