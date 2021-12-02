package datadog.trace.instrumentation.mongo;

import com.mongodb.internal.async.SingleResultCallback;
import net.bytebuddy.asm.Advice;

public class Arg0Advice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void wrap(
      @Advice.Argument(value = 0, readOnly = false) SingleResultCallback<Object> callback) {
    callback = CallbackWrapper.wrapIfRequired(callback);
  }
}
