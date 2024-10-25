package datadog.trace.instrumentation.jetty11;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import net.bytebuddy.asm.Advice;

/**
 * Because we are processing the initial request before the requestedSessionId is set, we must
 * update it when it is actually set.
 */
@RequiresRequestContext(RequestContextSlot.APPSEC)
public class SetRequestedSessionIdAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void setRequestedSessionId(
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Argument(0) final String requestedSessionId) {
    JettyBlockingHelper.maybeBlockOnSession(reqCtx, requestedSessionId);
  }
}
