package org.glassfish.jersey.client;

import net.bytebuddy.asm.Advice;

public class WrappingResponseCallbackAdvice {
  @Advice.OnMethodEnter
  public static void wrap(
      @Advice.Argument(0) ClientRequest request,
      @Advice.Argument(value = 1, readOnly = false) ResponseCallback callback) {
    callback = new WrappingResponseCallback(callback, request);
  }
}
