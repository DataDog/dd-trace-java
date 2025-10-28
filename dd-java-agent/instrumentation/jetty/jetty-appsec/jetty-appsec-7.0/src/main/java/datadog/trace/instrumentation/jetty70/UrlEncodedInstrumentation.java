package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.jetty70.RequestExtractParametersInstrumentation.REQUEST_REFERENCE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.InputStream;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(InstrumenterModule.class)
public class UrlEncodedInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public UrlEncodedInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.util.UrlEncoded";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("decodeTo")
            .and(takesArgument(0, InputStream.class))
            .and(takesArgument(1, named("org.eclipse.jetty.util.MultiMap")))
            .and(takesArgument(2, String.class))
            // there may be a 4th argument with the limit
            .and(isPublic()),
        getClass().getName() + "$UrlEncodedDecodeToAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class UrlEncodedDecodeToAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.Argument(value = 1, readOnly = false) MultiMap<String> map,
        @Advice.Local("origMap") MultiMap<String> origMap) {
      // check we're inside extractParameters in Request
      if (CallDepthThreadLocalMap.getCallDepth(Request.class) == 0) {
        return false;
      }
      if (!map.isEmpty()) {
        origMap = map;
        map = new MultiMap<>();
      }
      return true;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean relevantCall,
        @Advice.Argument(1) MultiMap<String> map, // this is our map, not the orig arg
        @Advice.Local("origMap") MultiMap<String> origMap,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (!relevantCall) {
        return;
      }

      if (map.isEmpty()) { // nothing was written
        return;
      }

      try {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Object, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        if (callback == null) {
          return;
        }
        Flow<Void> flow = callback.apply(reqCtx, map);
        Flow.Action action = flow.getAction();
        if (action instanceof Flow.Action.RequestBlockingAction) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
          if (blockResponseFunction != null) {
            blockResponseFunction.tryCommitBlockingResponse(
                reqCtx.getTraceSegment(),
                rba.getStatusCode(),
                rba.getBlockingContentType(),
                rba.getExtraHeaders());
            if (t == null) {
              t = new BlockingException("Blocked request (for UrlEncoded/decodeTo)");
              reqCtx.getTraceSegment().effectivelyBlocked();
            }
          }
        }
      } finally {
        origMap.putAll(map);
      }
    }
  }
}
