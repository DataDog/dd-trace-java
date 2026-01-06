package datadog.trace.instrumentation.mongo;

import com.mongodb.internal.async.SingleResultCallback;
import net.bytebuddy.asm.Advice;

public class Arg2Advice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrap(
      @Advice.Argument(value = 2, readOnly = false) SingleResultCallback<Object> callback) {
    callback = CallbackWrapper.wrapIfRequired(callback);
  }
}
