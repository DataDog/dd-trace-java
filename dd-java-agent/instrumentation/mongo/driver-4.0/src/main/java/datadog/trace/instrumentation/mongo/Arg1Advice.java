package datadog.trace.instrumentation.mongo;

import com.mongodb.internal.async.SingleResultCallback;
import net.bytebuddy.asm.Advice;

public class Arg1Advice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrap(
      @Advice.Argument(value = 1, readOnly = false) SingleResultCallback<Object> callback) {
    callback = CallbackWrapper.wrapIfRequired(callback);
  }
}
