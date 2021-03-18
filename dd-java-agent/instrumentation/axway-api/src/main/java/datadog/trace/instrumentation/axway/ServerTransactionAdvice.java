package datadog.trace.instrumentation.axway;

import static datadog.trace.instrumentation.axway.AxwayHTTPPluginDecorator.SERVER_TRANSACTION_CLASS;

import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;

public class ServerTransactionAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onEnter(
      @Advice.This Object thiz, @Advice.Argument(value = 0) final int responseCode) {
    if (null != SERVER_TRANSACTION_CLASS) {
      InstrumentationContext.get(SERVER_TRANSACTION_CLASS, int.class).put(thiz, responseCode);
    }
  }
}
