package datadog.trace.instrumentation.jetty_client12;

import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.FutureResponseListener;
import org.eclipse.jetty.client.Request;

public class LinkListenerAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void link(
      @Advice.This FutureResponseListener listener, @Advice.Argument(0) Request request) {
    InstrumentationContext.get(FutureResponseListener.class, Request.class).put(listener, request);
  }
}
