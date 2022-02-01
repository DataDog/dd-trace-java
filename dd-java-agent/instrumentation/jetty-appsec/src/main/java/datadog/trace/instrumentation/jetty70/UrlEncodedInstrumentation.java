package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.MultiMap;

@AutoService(Instrumenter.class)
public class UrlEncodedInstrumentation extends Instrumenter.AppSec {
  public UrlEncodedInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.util.UrlEncoded");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("decodeTo")
            .and(takesArgument(0, InputStream.class))
            .and(takesArgument(1, named("org.eclipse.jetty.util.MultiMap")))
            .and(takesArgument(2, String.class)),
        // there may be a 4th argument with the limit
        getClass().getName() + "$UrlEncodedDecodeToAdvice");
  }

  public static class UrlEncodedDecodeToAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Argument(1) MultiMap<String> map) {
      // we're inside extractParameters/getParameters in Request
      if (CallDepthThreadLocalMap.getCallDepth(Request.class) == 0) {
        return;
      }

      if (map.isEmpty()) {
        return;
      }

      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
      BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      RequestContext<Object> requestContext = agentSpan.getRequestContext();
      if (requestContext == null || callback == null) {
        return;
      }
      callback.apply(requestContext, map);
    }
  }
}
