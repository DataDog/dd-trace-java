package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.jetty70.RequestExtractParametersInstrumentation.REQUEST_REFERENCE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(Instrumenter.class)
public class UrlEncodedInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("decodeTo")
            .and(takesArgument(0, InputStream.class))
            .and(takesArgument(1, named("org.eclipse.jetty.util.MultiMap")))
            .and(takesArgument(2, String.class))
            // there may be a 4th argument with the limit
            .and(isPublic()),
        getClass().getName() + "$UrlEncodedDecodeToAdvice");
  }

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
        @Advice.Local("origMap") MultiMap<String> origMap) {
      if (!relevantCall) {
        return;
      }

      if (map.isEmpty()) { // nothing was written
        return;
      }

      try {
        AgentSpan agentSpan = activeSpan();
        if (agentSpan == null) {
          return;
        }

        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Object, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        RequestContext requestContext = agentSpan.getRequestContext();
        if (requestContext == null || callback == null) {
          return;
        }
        callback.apply(requestContext, map);
      } finally {
        origMap.putAll(map);
      }
    }
  }
}
