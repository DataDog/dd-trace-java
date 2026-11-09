package datadog.trace.instrumentation.jetty12;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.eclipse.jetty.session.AbstractSessionManager.RequestedSession;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

/**
 * Because we are processing the initial request before the requestedSession is set, we must update
 * it when it is actually set.
 */
@RequiresRequestContext(RequestContextSlot.APPSEC)
public class ResolveRequestedSessionIdAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void resolveRequestedSessionId(
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Return final RequestedSession requestedSession,
      @Advice.Thrown(readOnly = false) Throwable throwable) {
    final String requestedSessionId =
        requestedSession == null ? null : requestedSession.sessionId();
    if (requestedSessionId != null && reqCtx != null) {
      final CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      if (cbp == null) {
        return;
      }
      final BiFunction<RequestContext, String, Flow<Void>> addrCallback =
          cbp.getCallback(EVENTS.requestSession());
      if (addrCallback == null) {
        return;
      }
      final Flow<Void> flow = addrCallback.apply(reqCtx, requestedSessionId);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction
          && JettyBlockResponseHelper.tryCommitBlockingResponse(
              reqCtx, (Flow.Action.RequestBlockingAction) action)) {
        // only abort the request when the blocking response was actually committed; a bare throw
        // would be swallowed by the suppress handler, so replace the thrown value instead
        if (throwable == null) {
          throwable = new BlockingException("Blocked request (for sessionId)");
        }
      }
    }
  }
}
